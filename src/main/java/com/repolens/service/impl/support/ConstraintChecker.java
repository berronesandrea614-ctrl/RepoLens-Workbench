package com.repolens.service.impl.support;

import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.domain.vo.ReconciliationVO;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Constraint violation detector (Feature B P2).
 * Pure static functions — no Spring dependencies, fully unit-testable.
 *
 * <p>Failure-safe: individual rule check failures are caught and logged; never crash reconciliation.
 * SEMANTIC rules (checkable=false) are silently skipped.
 */
@Slf4j
public final class ConstraintChecker {

    private ConstraintChecker() {}

    /**
     * Checks all parsed rules against actual file changes and tool call log.
     *
     * @param rules       list of parsed constraint rules (may be empty)
     * @param changes     file changes for the session
     * @param toolCallLogs tool call logs for the session
     * @return list of violations; empty if no violations or no rules
     */
    public static List<ReconciliationVO.ConstraintViolation> checkViolations(
            List<ConstraintRule> rules,
            List<FileChangeLogEntity> changes,
            List<ToolCallLogEntity> toolCallLogs) {

        if (rules == null || rules.isEmpty()) return List.of();

        List<ReconciliationVO.ConstraintViolation> violations = new ArrayList<>();

        for (ConstraintRule rule : rules) {
            if (!rule.checkable()) continue; // SEMANTIC → skip
            try {
                List<ReconciliationVO.ConstraintViolation> found =
                        checkSingleRule(rule, changes, toolCallLogs);
                violations.addAll(found);
            } catch (Exception ex) {
                log.warn("constraint check failed for rule='{}', skipping. err={}",
                        rule.rawText(), ex.getMessage());
            }
        }

        return violations;
    }

    // ── Per-rule dispatching ──────────────────────────────────────────────────

    private static List<ReconciliationVO.ConstraintViolation> checkSingleRule(
            ConstraintRule rule,
            List<FileChangeLogEntity> changes,
            List<ToolCallLogEntity> toolCallLogs) {

        return switch (rule.type()) {
            case ConstraintRule.PATH_FORBIDDEN     -> checkPathForbidden(rule, changes);
            case ConstraintRule.FILETYPE_FORBIDDEN -> checkFiletypeForbidden(rule, changes);
            case ConstraintRule.NO_NEW_DEP         -> checkNoNewDep(rule, changes);
            case ConstraintRule.MUST_VERIFY        -> checkMustVerify(rule, changes, toolCallLogs);
            default                                -> List.of();
        };
    }

    // ── PATH_FORBIDDEN ────────────────────────────────────────────────────────

    private static List<ReconciliationVO.ConstraintViolation> checkPathForbidden(
            ConstraintRule rule, List<FileChangeLogEntity> changes) {

        if (rule.pattern() == null || rule.pattern().isBlank()) return List.of();

        List<String> matched = new ArrayList<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null && matchesPathPattern(c.getFilePath(), rule.pattern())) {
                matched.add(c.getFilePath());
            }
        }
        return matched.isEmpty() ? List.of() : List.of(toViolation(rule, matched));
    }

    /**
     * Checks if a file path matches a forbidden path pattern.
     * Pattern can be a directory name ("migration"), a path prefix ("src/legacy"),
     * or a path with trailing slash ("src/db/").
     * Package-visible for unit testing.
     */
    static boolean matchesPathPattern(String filePath, String pattern) {
        if (filePath == null || pattern == null) return false;
        String fp = filePath.replace('\\', '/');
        String p  = pattern.replace('\\', '/');
        // Remove trailing slash from pattern for cleaner matching
        String pNoSlash = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;

        // Exact path segment match: /migration/ or /migration at end
        if (fp.contains("/" + pNoSlash + "/") || fp.endsWith("/" + pNoSlash)) return true;
        // Path starts with pattern (absolute-ish path)
        if (fp.startsWith(pNoSlash + "/") || fp.equals(pNoSlash)) return true;
        // Pattern with multiple segments — substring match
        if (p.contains("/") && fp.contains(p)) return true;
        return false;
    }

    // ── FILETYPE_FORBIDDEN ────────────────────────────────────────────────────

    private static List<ReconciliationVO.ConstraintViolation> checkFiletypeForbidden(
            ConstraintRule rule, List<FileChangeLogEntity> changes) {

        if (rule.pattern() == null || rule.pattern().isBlank()) return List.of();

        List<String> matched = new ArrayList<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() != null && matchesGlobPattern(c.getFilePath(), rule.pattern())) {
                matched.add(c.getFilePath());
            }
        }
        return matched.isEmpty() ? List.of() : List.of(toViolation(rule, matched));
    }

    /**
     * Checks if a file path matches a simple glob pattern (e.g., *.lock, *.sql).
     * Matches against both the filename component and the full path.
     * Package-visible for unit testing.
     */
    static boolean matchesGlobPattern(String filePath, String glob) {
        if (filePath == null || glob == null) return false;
        String fp = filePath.replace('\\', '/');
        String fileName = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;

        String regexPart = globToRegex(glob);

        // Try matching just the filename
        if (fileName.matches(regexPart)) return true;
        // Try matching the full path
        if (fp.matches(regexPart)) return true;
        return false;
    }

    /** Converts a simple file glob to a Java regex string. */
    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*'  -> sb.append(".*");
                case '?'  -> sb.append(".");
                case '.'  -> sb.append("\\.");
                case '['  -> sb.append("\\[");
                case ']'  -> sb.append("\\]");
                case '('  -> sb.append("\\(");
                case ')'  -> sb.append("\\)");
                case '{'  -> sb.append("\\{");
                case '}'  -> sb.append("\\}");
                case '\\' -> sb.append("\\\\");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── NO_NEW_DEP ────────────────────────────────────────────────────────────

    private static List<ReconciliationVO.ConstraintViolation> checkNoNewDep(
            ConstraintRule rule, List<FileChangeLogEntity> changes) {

        List<String> matched = new ArrayList<>();
        for (FileChangeLogEntity c : changes) {
            if (c.getFilePath() == null) continue;
            if (!isDependencyManifest(c.getFilePath())) continue;
            // Detect newly added dependency: new content has more dep lines than old
            String oldContent = c.getOldContent() != null ? c.getOldContent() : "";
            String newContent = c.getNewContent() != null ? c.getNewContent() : "";
            if (newContent.length() > oldContent.length()
                    && countDependencyLines(newContent) > countDependencyLines(oldContent)) {
                matched.add(c.getFilePath());
            }
        }
        return matched.isEmpty() ? List.of() : List.of(toViolation(rule, matched));
    }

    /**
     * Returns true if the file path is a known dependency manifest.
     * Package-visible for unit testing.
     */
    static boolean isDependencyManifest(String filePath) {
        if (filePath == null) return false;
        String fp = filePath.replace('\\', '/');
        String name = fp.contains("/") ? fp.substring(fp.lastIndexOf('/') + 1) : fp;
        return switch (name.toLowerCase()) {
            case "pom.xml", "package.json", "package-lock.json", "yarn.lock",
                 "requirements.txt", "build.gradle", "build.gradle.kts",
                 "pipfile", "pipfile.lock", "pyproject.toml", "setup.py",
                 "go.mod", "go.sum", "gemfile", "gemfile.lock" -> true;
            default -> false;
        };
    }

    /**
     * Counts dependency declaration signals in a manifest file text.
     * Uses substring occurrence counting (not line counting) so single-line
     * XML/JSON content is handled correctly.
     * Heuristic covering pom.xml, package.json, build.gradle, requirements.txt.
     */
    private static int countDependencyLines(String content) {
        if (content == null || content.isBlank()) return 0;
        int count = 0;
        // pom.xml: count <dependency> opening tags
        count += countOccurrences(content, "<dependency>");
        // package.json: count "name":"version" pairs (each dep has one `":"` adjacent pair)
        count += countOccurrences(content, "\":\"");
        // gradle: count configuration declaration lines
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith("implementation ")
                    || t.startsWith("api \"")
                    || t.startsWith("compile ")
                    || t.startsWith("testImplementation ")
                    || t.startsWith("runtimeOnly ")
                    || t.startsWith("compileOnly ")) {
                count++;
            }
        }
        // requirements.txt: count non-comment package lines (contain == or >=)
        if (!content.contains("<") && !content.contains("{")) {
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (!t.isEmpty() && !t.startsWith("#")
                        && (t.contains("==") || t.contains(">=") || t.contains("<="))) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countOccurrences(String text, String marker) {
        if (text == null || marker == null || marker.isEmpty()) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(marker, idx)) >= 0) {
            count++;
            idx += marker.length();
        }
        return count;
    }

    // ── MUST_VERIFY ───────────────────────────────────────────────────────────

    private static List<ReconciliationVO.ConstraintViolation> checkMustVerify(
            ConstraintRule rule,
            List<FileChangeLogEntity> changes,
            List<ToolCallLogEntity> toolCallLogs) {

        // Only check if there were actual changes to verify
        if (changes == null || changes.isEmpty()) return List.of();

        boolean hasVerification = toolCallLogs != null && toolCallLogs.stream()
                .anyMatch(t -> "runVerification".equals(t.getToolName()));

        return hasVerification ? List.of() : List.of(toViolation(rule, List.of()));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ReconciliationVO.ConstraintViolation toViolation(
            ConstraintRule rule, List<String> matchedFiles) {
        return ReconciliationVO.ConstraintViolation.builder()
                .ruleType(rule.type())
                .rawText(rule.rawText())
                .matchedFiles(List.copyOf(matchedFiles))
                .severity(rule.severity())
                .build();
    }
}
