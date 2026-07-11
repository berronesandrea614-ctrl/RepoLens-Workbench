package com.repolens.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Offline retrieval evaluation harness (P2.7 T4).
 *
 * <p>Loads the golden set at {@code eval/groundtruth_retrieval.json} and computes retrieval quality
 * metrics against a pluggable {@link RetrievalRunner}. The metric computation (precision@k,
 * recall@k, refusal accuracy) is real and reusable; only the default runner is a deterministic
 * offline stub so the harness runs green without a live DB / Milvus.</p>
 *
 * <p>This is an eval, not a unit gate: the class name intentionally does NOT match Surefire's
 * default {@code *Test}/{@code *Tests} include patterns, so it is skipped by a plain {@code mvn test}
 * and only runs when explicitly selected:</p>
 * <pre>mvn test -Dtest=RetrievalEvalHarness</pre>
 *
 * <h2>Plugging a live runner</h2>
 * A production run wires {@link RagRetrievalService#retrieve(Long, Long, String, Integer)} into a
 * {@link RetrievalRunner}, e.g.:
 * <pre>{@code
 * RetrievalRunner live = query -> ragRetrievalService
 *         .retrieve(repoId, userId, query, TOP_K)
 *         .getResults().stream()
 *         .map(RagChunkVO::getFilePath)
 *         .collect(Collectors.toList());
 * EvalReport report = evaluate(loadGoldenCases(), live, TOP_K);
 * }</pre>
 * The file-path comparison is basename-based (see {@link #basename}) so absolute chunk paths from
 * the live service match the short expected file names in the golden set.
 */
class RetrievalEvalHarness {

    /** Retrieval cut-off used for precision@k / recall@k and passed to a live runner. */
    private static final int TOP_K = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Pluggable retrieval boundary: given a user query, return the ranked list of file paths the
     * retriever surfaced (best first). A live implementation delegates to {@link RagRetrievalService}.
     */
    @FunctionalInterface
    interface RetrievalRunner {
        List<String> filesFor(String query);
    }

    /** One golden-set case. */
    private record EvalCase(String query, boolean relevant, List<String> expectedFiles) {
    }

    /** Per-query evaluation outcome. */
    private record CaseResult(String query, boolean relevant, List<String> expected,
                              List<String> retrieved, double precision, double recall,
                              boolean hit, boolean refusalCorrect) {
    }

    /** Aggregated report over all cases. */
    record EvalReport(List<CaseResult> results, int total, int positives, int negatives,
                      double precisionAvg, double recallAvg, double refusalAccuracy) {
    }

    // ------------------------------------------------------------------------------------------
    // Test entry point
    // ------------------------------------------------------------------------------------------

    @Test
    void runRetrievalEval() throws Exception {
        List<EvalCase> cases = loadGoldenCases();
        Assertions.assertFalse(cases.isEmpty(), "golden set must not be empty");

        // Default = deterministic offline stub. Swap this line for a live runner (see class javadoc).
        RetrievalRunner runner = new KeywordStubRunner();

        EvalReport report = evaluate(cases, runner, TOP_K);
        System.out.println(renderReport(report, TOP_K));

        // Sanity gates on the harness itself (loose, so it stays green while metrics evolve).
        Assertions.assertEquals(cases.size(), report.total(), "every case must be scored");
        assertUnitInterval("precisionAvg", report.precisionAvg());
        assertUnitInterval("recallAvg", report.recallAvg());
        assertUnitInterval("refusalAccuracy", report.refusalAccuracy());
        // The stub is constructed so it never fabricates evidence for out-of-domain queries.
        Assertions.assertEquals(1.0, report.refusalAccuracy(), 1e-9,
                "stub runner must refuse all out-of-domain queries");
    }

    // ------------------------------------------------------------------------------------------
    // Metric computation (real & reusable)
    // ------------------------------------------------------------------------------------------

    /**
     * Core, runner-agnostic evaluation. For positive cases (relevant=true) computes precision@k and
     * recall@k over the expected file set; for negative cases (relevant=false, out-of-domain)
     * scores refusal accuracy: a case is correctly refused iff the runner returns no evidence.
     */
    static EvalReport evaluate(List<EvalCase> cases, RetrievalRunner runner, int topK) {
        List<CaseResult> results = new ArrayList<>();
        double precisionSum = 0.0;
        double recallSum = 0.0;
        int positives = 0;
        int negatives = 0;
        int refusalsCorrect = 0;

        for (EvalCase c : cases) {
            List<String> retrievedRaw = runner.filesFor(c.query());
            List<String> retrieved = retrievedRaw == null ? List.of() : retrievedRaw.stream()
                    .filter(p -> p != null && !p.isBlank())
                    .limit(topK)
                    .collect(Collectors.toList());

            Set<String> retrievedSet = retrieved.stream()
                    .map(RetrievalEvalHarness::basename)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> expectedSet = c.expectedFiles().stream()
                    .map(RetrievalEvalHarness::basename)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            if (c.relevant()) {
                positives++;
                long tp = retrievedSet.stream().filter(expectedSet::contains).count();
                double precision = retrievedSet.isEmpty() ? 0.0 : (double) tp / retrievedSet.size();
                double recall = expectedSet.isEmpty() ? 0.0 : (double) tp / expectedSet.size();
                boolean hit = tp == expectedSet.size(); // all expected files covered
                precisionSum += precision;
                recallSum += recall;
                results.add(new CaseResult(c.query(), true, new ArrayList<>(expectedSet),
                        new ArrayList<>(retrievedSet), precision, recall, hit, false));
            } else {
                negatives++;
                boolean refusalCorrect = retrievedSet.isEmpty();
                if (refusalCorrect) {
                    refusalsCorrect++;
                }
                results.add(new CaseResult(c.query(), false, List.of(),
                        new ArrayList<>(retrievedSet), Double.NaN, Double.NaN, refusalCorrect,
                        refusalCorrect));
            }
        }

        double precisionAvg = positives == 0 ? 0.0 : precisionSum / positives;
        double recallAvg = positives == 0 ? 0.0 : recallSum / positives;
        double refusalAccuracy = negatives == 0 ? 0.0 : (double) refusalsCorrect / negatives;
        return new EvalReport(results, cases.size(), positives, negatives, precisionAvg, recallAvg,
                refusalAccuracy);
    }

    private static String renderReport(EvalReport r, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== Retrieval Eval Report (k=").append(topK).append(") ====================\n");
        sb.append(String.format(Locale.ROOT, "%-30s %-8s %-9s %-8s %s%n",
                "query", "type", "prec@k", "rec@k", "outcome"));
        sb.append("--------------------------------------------------------------------------------\n");
        for (CaseResult c : r.results()) {
            String type = c.relevant() ? "positive" : "negative";
            String prec = c.relevant() ? String.format(Locale.ROOT, "%.2f", c.precision()) : "-";
            String rec = c.relevant() ? String.format(Locale.ROOT, "%.2f", c.recall()) : "-";
            String outcome;
            if (c.relevant()) {
                outcome = (c.hit() ? "HIT " : "MISS") + " retrieved=" + c.retrieved();
            } else {
                outcome = (c.refusalCorrect() ? "REFUSED(ok)" : "LEAKED " + c.retrieved());
            }
            sb.append(String.format(Locale.ROOT, "%-30s %-8s %-9s %-8s %s%n",
                    truncate(c.query(), 30), type, prec, rec, outcome));
        }
        sb.append("--------------------------------------------------------------------------------\n");
        sb.append(String.format(Locale.ROOT, "total=%d  positives=%d  negatives=%d%n",
                r.total(), r.positives(), r.negatives()));
        sb.append(String.format(Locale.ROOT,
                "Precision@%d avg = %.3f   Recall@%d avg = %.3f   Refusal accuracy = %.3f (%d/%d)%n",
                topK, r.precisionAvg(), topK, r.recallAvg(), r.refusalAccuracy(),
                (int) Math.round(r.refusalAccuracy() * r.negatives()), r.negatives()));
        sb.append("================================================================================\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------
    // Golden-set loading
    // ------------------------------------------------------------------------------------------

    static List<EvalCase> loadGoldenCases() throws Exception {
        Path path = resolveGoldenPath();
        JsonNode root = MAPPER.readTree(Files.readAllBytes(path));
        JsonNode cases = root.get("cases");
        if (cases == null || !cases.isArray()) {
            throw new IllegalStateException("golden set missing 'cases' array: " + path);
        }
        List<EvalCase> out = new ArrayList<>();
        for (JsonNode node : cases) {
            String query = node.path("query").asText();
            boolean relevant = node.path("relevant").asBoolean(false);
            List<String> expected = new ArrayList<>();
            JsonNode ef = node.get("expected_files");
            if (ef != null && ef.isArray()) {
                for (JsonNode f : ef) {
                    expected.add(f.asText());
                }
            }
            out.add(new EvalCase(query, relevant, expected));
        }
        return out;
    }

    /** Resolve the golden set relative to the project root ({@code user.dir}), with fallbacks. */
    private static Path resolveGoldenPath() {
        String userDir = System.getProperty("user.dir", ".");
        List<Path> candidates = List.of(
                Path.of(userDir, "eval", "groundtruth_retrieval.json"),
                Path.of(userDir, "..", "eval", "groundtruth_retrieval.json"),
                Path.of("eval", "groundtruth_retrieval.json"));
        for (Path p : candidates) {
            if (Files.exists(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException(
                "eval/groundtruth_retrieval.json not found relative to user.dir=" + userDir);
    }

    // ------------------------------------------------------------------------------------------
    // Default offline runner: deterministic keyword retriever over a tiny in-memory demo index.
    // Mirrors the production keyword-degradation path closely enough to exercise the metrics
    // (imperfect precision, correct refusal) WITHOUT any live DB / Milvus. Replace with a live
    // RagRetrievalService-backed runner for real numbers (see class javadoc).
    // ------------------------------------------------------------------------------------------

    static final class KeywordStubRunner implements RetrievalRunner {

        /** Minimal token "index" of the demo repo (repoId=1): filePath -> indexed terms. */
        private static final Map<String, String> INDEX = new LinkedHashMap<>();

        static {
            INDEX.put("UserController.java", "UserController createUser getUserById User");
            INDEX.put("UserService.java", "UserService createUser getUserById findById User");
            INDEX.put("UserRepository.java", "UserRepository save findById User");
            INDEX.put("User.java", "User entity");
        }

        @Override
        public List<String> filesFor(String query) {
            String[] tokens = query.toLowerCase(Locale.ROOT).split("\\s+");
            // Score each file by how many query tokens it contains (substring match).
            List<Map.Entry<String, Long>> scored = new ArrayList<>();
            for (Map.Entry<String, String> e : INDEX.entrySet()) {
                String content = e.getValue().toLowerCase(Locale.ROOT);
                long score = Arrays.stream(tokens)
                        .filter(t -> !t.isBlank())
                        .filter(content::contains)
                        .count();
                if (score > 0) {
                    scored.add(Map.entry(e.getKey(), score));
                }
            }
            // Rank by score desc, tie-break by filename for determinism.
            scored.sort((a, b) -> {
                int cmp = Long.compare(b.getValue(), a.getValue());
                return cmp != 0 ? cmp : a.getKey().compareTo(b.getKey());
            });
            return scored.stream().map(Map.Entry::getKey).collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /** Compare on basename so absolute chunk paths from a live service match short golden names. */
    private static String basename(String path) {
        if (path == null) {
            return "";
        }
        String norm = path.replace('\\', '/');
        int idx = norm.lastIndexOf('/');
        return idx >= 0 ? norm.substring(idx + 1) : norm;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private static void assertUnitInterval(String name, double v) {
        Assertions.assertTrue(v >= 0.0 && v <= 1.0, name + " out of [0,1]: " + v);
    }
}
