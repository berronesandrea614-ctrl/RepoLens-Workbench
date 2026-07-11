package com.repolens.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class VerificationOutputParser {

    private static final Pattern MAVEN_ERROR =
            Pattern.compile("\\[ERROR\\]\\s+(.+\\.java):\\[(\\d+),\\d+\\]\\s+(.+)");

    private static final Pattern JUNIT_AT =
            Pattern.compile("\\s+at\\s+[\\w.$]+\\.([\\w$]+)\\((.+\\.java):(\\d+)\\)");

    private static final Pattern JUNIT_FAILED =
            Pattern.compile("(.+?)\\s*>\\s*(.+?)\\s+FAILED");

    private static final Pattern TSC_ERROR =
            Pattern.compile("(.+\\.tsx?)\\((\\d+),\\d+\\): error (TS\\d+): (.+)");

    public record Failure(String file, int line, String symbol, String message, String context) {}

    public List<Failure> parse(String output, Path shadowDir) {
        if (output == null || output.isBlank()) return List.of();
        List<Failure> failures = new ArrayList<>();
        try {
            failures.addAll(parseMavenErrors(output, shadowDir));
            if (failures.isEmpty()) {
                failures.addAll(parseJunitFailures(output, shadowDir));
            }
            if (failures.isEmpty()) {
                failures.addAll(parseTscErrors(output, shadowDir));
            }
        } catch (Exception e) {
            log.warn("VerificationOutputParser.parse failed (safe): {}", e.getMessage());
        }
        return failures;
    }

    private List<Failure> parseMavenErrors(String output, Path shadowDir) {
        List<Failure> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = MAVEN_ERROR.matcher(line);
            if (m.find()) {
                String filePath = m.group(1);
                int lineNo = parseInt(m.group(2));
                String msg = m.group(3).trim();
                String relFile = extractRelativePath(filePath);
                String context = extractContext(shadowDir, relFile, filePath, lineNo);
                result.add(new Failure(relFile, lineNo, "", msg, context));
            }
        }
        return result;
    }

    private List<Failure> parseJunitFailures(String output, Path shadowDir) {
        List<Failure> result = new ArrayList<>();
        String[] lines = output.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher fm = JUNIT_FAILED.matcher(lines[i]);
            if (!fm.find()) continue;
            String testClass = fm.group(1).trim();
            String testMethod = fm.group(2).trim();
            for (int j = i + 1; j < Math.min(i + 30, lines.length); j++) {
                Matcher am = JUNIT_AT.matcher(lines[j]);
                if (am.find()) {
                    String javaFile = am.group(2);
                    int lineNo = parseInt(am.group(3));
                    // 跳过框架内置类（JUnit / JDK / 第三方），找项目的测试代码
                    if (javaFile.contains("org/junit") || javaFile.contains("org.junit")
                            || javaFile.contains("Assertions.java") || javaFile.contains("jdk.internal")
                            || javaFile.contains("org.opentest4j")
                            || javaFile.contains("org.opentest")) {
                        continue;
                    }
                    String relFile = extractRelativePath(javaFile);
                    String context = extractContext(shadowDir, relFile, javaFile, lineNo);
                    result.add(new Failure(relFile, lineNo, testClass + "#" + testMethod,
                            "JUnit test failed", context));
                    break;
                }
            }
        }
        return result;
    }

    private List<Failure> parseTscErrors(String output, Path shadowDir) {
        List<Failure> result = new ArrayList<>();
        for (String line : output.split("\n")) {
            Matcher m = TSC_ERROR.matcher(line);
            if (m.find()) {
                String filePath = m.group(1);
                int lineNo = parseInt(m.group(2));
                String errorCode = m.group(3);
                String msg = m.group(4).trim();
                String relFile = extractRelativePath(filePath);
                String context = extractContext(shadowDir, relFile, filePath, lineNo);
                result.add(new Failure(relFile, lineNo, errorCode, msg, context));
            }
        }
        return result;
    }

    private String extractContext(Path shadowDir, String relFile, String absFile, int lineNo) {
        if (lineNo <= 0) return "";
        try {
            Path target = resolveFilePath(shadowDir, relFile, absFile);
            if (target == null || !Files.isRegularFile(target)) return "";
            List<String> fileLines = Files.readAllLines(target, StandardCharsets.UTF_8);
            if (lineNo > fileLines.size()) return "";

            int idx = lineNo - 1;
            int start = Math.max(0, idx - 40);
            for (int i = idx; i >= start; i--) {
                String l = fileLines.get(i).trim();
                if (l.startsWith("public ") || l.startsWith("private ") || l.startsWith("protected ")
                        || l.startsWith("@Test") || l.startsWith("void ")) {
                    start = i;
                    break;
                }
            }
            int end = Math.min(fileLines.size() - 1, idx + 40);
            int depth = 0;
            boolean started = false;
            for (int i = start; i <= Math.min(fileLines.size() - 1, idx + 60); i++) {
                for (char c : fileLines.get(i).toCharArray()) {
                    if (c == '{') { depth++; started = true; }
                    if (c == '}') { depth--; }
                }
                if (started && depth <= 0) { end = i; break; }
            }
            List<String> slice = fileLines.subList(start, Math.min(end + 1, fileLines.size()));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < slice.size(); i++) {
                sb.append(start + i + 1).append(": ").append(slice.get(i)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("extractContext failed for {}: {}", relFile, e.getMessage());
            return "";
        }
    }

    private Path resolveFilePath(Path shadowDir, String relFile, String absFile) {
        if (shadowDir != null && relFile != null && !relFile.isBlank()) {
            Path candidate = shadowDir.resolve(relFile);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        if (absFile != null) {
            Path abs = Path.of(absFile);
            if (Files.isRegularFile(abs)) return abs;
        }
        return null;
    }

    private String extractRelativePath(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf("/src/");
        if (idx >= 0) return "src" + path.substring(idx + 4);
        idx = path.lastIndexOf("\\src\\");
        if (idx >= 0) return "src" + path.substring(idx + 4).replace("\\", "/");
        return path;
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
