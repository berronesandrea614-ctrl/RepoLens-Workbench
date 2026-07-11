package com.repolens.kernel.verify;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把构建/测试的原始输出解析为结构化 {@link Failure}，并补"函数级上下文"喂回模型自愈。
 *
 * <p>覆盖 maven(javac)/gradle/surefire-junit/tsc-npm/pytest/go/cargo。从零实现，
 * 不抄旧 {@code VerificationOutputParser}——旧版只有 maven/junit/tsc 三种且上下文抽取薄。
 * 定位不到具体位置的失败也照实登记（file 空、line 0），绝不因"解析不出"就假装没有失败。
 */
@Component("kernelVerificationOutputParser")
public class VerificationOutputParser {

    // javac 经 maven 包装：[ERROR] /abs/Foo.java:[12,20] message  也兼容 :12: error: 形式
    private static final Pattern JAVAC_BRACKET = Pattern.compile("(?m)^\\[ERROR]\\s+(\\S+\\.java):\\[(\\d+),\\d+]\\s+(.*)$");
    private static final Pattern JAVAC_COLON   = Pattern.compile("(?m)^(\\S+\\.java):(\\d+):\\s*(?:error|错误)?:?\\s*(.*)$");
    // surefire: [ERROR] com.foo.BarTest.testX ... <<< FAILURE!  之后栈帧 at com.foo.BarTest.testX(BarTest.java:42)
    private static final Pattern SUREFIRE_HEAD = Pattern.compile("(?m)^\\[ERROR]\\s+([\\w.]+)\\.(\\w+)\\b.*<<<\\s+(FAILURE|ERROR)!");
    private static final Pattern STACK_AT      = Pattern.compile("at\\s+[\\w.$]+\\((\\w+\\.java):(\\d+)\\)");
    // tsc: src/foo.ts(12,5): error TS2322: message
    private static final Pattern TSC = Pattern.compile("(?m)^(\\S+\\.tsx?)\\((\\d+),\\d+\\):\\s*error\\s+(TS\\d+):\\s*(.*)$");
    // pytest: path/test_foo.py:12: SomeError  /  FAILED path/test_foo.py::test_x
    private static final Pattern PYTEST_LOC    = Pattern.compile("(?m)^(\\S+\\.py):(\\d+):\\s*(.*(?:Error|assert|Exception).*)$");
    private static final Pattern PYTEST_FAILED = Pattern.compile("(?m)^FAILED\\s+(\\S+\\.py)::(\\S+)");
    // go: ./foo.go:12:5: message
    private static final Pattern GO = Pattern.compile("(?m)^(\\S+\\.go):(\\d+):(?:\\d+:)?\\s*(.*)$");
    // cargo: error[E0433]: msg  \n   --> src/main.rs:12:5
    private static final Pattern CARGO = Pattern.compile("(?m)^error(?:\\[(\\w+)])?:\\s*(.*)\\n(?:.*\\n)?\\s*-->\\s*(\\S+\\.rs):(\\d+):\\d+");

    public List<Failure> parse(String output, BuildTarget target, Path shadowDir) {
        List<Failure> failures = new ArrayList<>();
        if (output == null || output.isBlank() || target == null) {
            return failures;
        }
        switch (target) {
            case MAVEN, GRADLE -> {
                failures.addAll(parseJavac(output));
                failures.addAll(parseSurefire(output));
            }
            case NPM -> failures.addAll(parseTsc(output));
            case PYTHON -> failures.addAll(parsePytest(output));
            case GO -> failures.addAll(parseRegex(output, GO, 1, 2, 3, ""));
            case RUST -> failures.addAll(parseCargo(output));
        }
        return withContext(failures, shadowDir);
    }

    private List<Failure> parseJavac(String output) {
        List<Failure> out = new ArrayList<>();
        Matcher m = JAVAC_BRACKET.matcher(output);
        while (m.find()) {
            out.add(Failure.of(m.group(1), Integer.parseInt(m.group(2)), m.group(3).trim()));
        }
        if (out.isEmpty()) {
            Matcher c = JAVAC_COLON.matcher(output);
            while (c.find()) {
                out.add(Failure.of(c.group(1), Integer.parseInt(c.group(2)), c.group(3).trim()));
            }
        }
        return out;
    }

    private List<Failure> parseSurefire(String output) {
        List<Failure> out = new ArrayList<>();
        Matcher m = SUREFIRE_HEAD.matcher(output);
        while (m.find()) {
            String cls = m.group(1);
            String method = m.group(2);
            String simple = cls.contains(".") ? cls.substring(cls.lastIndexOf('.') + 1) : cls;
            int line = 0;
            String file = "";
            // 在该失败之后就近找一条指向该测试类的栈帧，取行号
            Matcher at = STACK_AT.matcher(output.substring(m.end()));
            while (at.find()) {
                if (at.group(1).equals(simple + ".java")) {
                    file = at.group(1);
                    line = Integer.parseInt(at.group(2));
                    break;
                }
            }
            out.add(new Failure(file, line, cls + "#" + method, "测试失败：" + method, ""));
        }
        return out;
    }

    private List<Failure> parseTsc(String output) {
        List<Failure> out = new ArrayList<>();
        Matcher m = TSC.matcher(output);
        while (m.find()) {
            out.add(Failure.of(m.group(1), Integer.parseInt(m.group(2)), m.group(4).trim()).withSymbol(m.group(3)));
        }
        return out;
    }

    private List<Failure> parsePytest(String output) {
        List<Failure> out = new ArrayList<>();
        Matcher m = PYTEST_LOC.matcher(output);
        while (m.find()) {
            out.add(Failure.of(m.group(1), Integer.parseInt(m.group(2)), m.group(3).trim()));
        }
        Matcher f = PYTEST_FAILED.matcher(output);
        while (f.find()) {
            out.add(new Failure(f.group(1), 0, f.group(2), "测试失败：" + f.group(2), ""));
        }
        return out;
    }

    private List<Failure> parseCargo(String output) {
        List<Failure> out = new ArrayList<>();
        Matcher m = CARGO.matcher(output);
        while (m.find()) {
            String code = m.group(1) == null ? "" : m.group(1);
            out.add(Failure.of(m.group(3), Integer.parseInt(m.group(4)), m.group(2).trim()).withSymbol(code));
        }
        return out;
    }

    private List<Failure> parseRegex(String output, Pattern p, int fileG, int lineG, int msgG, String sym) {
        List<Failure> out = new ArrayList<>();
        Matcher m = p.matcher(output);
        while (m.find()) {
            out.add(Failure.of(m.group(fileG), Integer.parseInt(m.group(lineG)), m.group(msgG).trim()).withSymbol(sym));
        }
        return out;
    }

    /** 为每条能定位到文件+行的失败，补上"所在函数签名 + 邻近代码窗口"。 */
    private List<Failure> withContext(List<Failure> failures, Path shadowDir) {
        if (shadowDir == null) {
            return failures;
        }
        List<Failure> out = new ArrayList<>(failures.size());
        for (Failure f : failures) {
            if (f.file().isBlank() || f.line() <= 0) {
                out.add(f);
                continue;
            }
            out.add(f.withContext(enclosingContext(shadowDir, f.file(), f.line())));
        }
        return out;
    }

    private String enclosingContext(Path shadowDir, String file, int line) {
        try {
            Path p = resolveInShadow(shadowDir, file);
            if (p == null || !Files.isRegularFile(p)) {
                return "";
            }
            List<String> lines = Files.readAllLines(p);
            int idx = Math.min(line, lines.size()) - 1;
            if (idx < 0) {
                return "";
            }
            String enclosing = null;
            for (int i = idx; i >= 0 && i >= idx - 200; i--) {
                String l = lines.get(i).trim();
                if (looksLikeDeclaration(l)) {
                    enclosing = l;
                    break;
                }
            }
            StringBuilder sb = new StringBuilder();
            if (enclosing != null) {
                sb.append("enclosing: ").append(enclosing).append('\n');
            }
            int from = Math.max(0, idx - 2);
            int to = Math.min(lines.size() - 1, idx + 2);
            for (int i = from; i <= to; i++) {
                sb.append(i == idx ? ">> " : "   ").append(i + 1).append(": ").append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean looksLikeDeclaration(String l) {
        if (l.startsWith("//") || l.startsWith("*") || l.startsWith("import") || l.startsWith("package")) {
            return false;
        }
        // 覆盖 java/ts/py/go/rust 的方法/函数/类声明启发式
        return l.matches(".*\\b(class|interface|enum|record)\\b.*")
                || l.startsWith("def ")
                || l.startsWith("func ")
                || l.startsWith("fn ") || l.contains(" fn ")
                || (l.contains("(") && l.contains(")") && l.endsWith("{")
                    && l.matches(".*\\b(public|private|protected|static|void|async|function|[A-Z]\\w+)\\b.*"));
    }

    private Path resolveInShadow(Path shadowDir, String file) {
        Path fp = Path.of(file);
        if (fp.isAbsolute()) {
            return fp;
        }
        return shadowDir.resolve(file).normalize();
    }
}
