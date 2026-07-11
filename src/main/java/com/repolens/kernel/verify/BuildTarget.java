package com.repolens.kernel.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 构建体系探测与离线命令生成。
 *
 * <p>验证一律走**离线**命令（{@code -o} / {@code --offline} / {@code GOPROXY=off}），
 * 配合 {@link VerificationRunner} 的断网沙盒，确保 agent 无法联网取巧造"通过"假象。
 */
public enum BuildTarget {

    MAVEN {
        @Override public boolean matches(Path dir) { return Files.exists(dir.resolve("pom.xml")); }
        // 用 compiler:compile 目标而非 compile 阶段：只做"能否编译"的语义检查，跳过 resources 等阶段插件，
        // 避免最小工程离线跑不到那些插件时把"插件解析失败"误当成编译失败。不加 -q，保留编译器逐条诊断供解析。
        @Override public List<String> compileCommand() { return List.of("mvn", "-o", "-B", "compiler:compile"); }
        @Override public List<String> testCommand()    { return List.of("mvn", "-o", "-B", "test"); }
    },
    GRADLE {
        @Override public boolean matches(Path dir) {
            return Files.exists(dir.resolve("build.gradle")) || Files.exists(dir.resolve("build.gradle.kts"));
        }
        @Override public List<String> compileCommand() { return List.of(gradle(), "--offline", "-q", "compileJava"); }
        @Override public List<String> testCommand()    { return List.of(gradle(), "--offline", "-q", "test"); }
    },
    NPM {
        @Override public boolean matches(Path dir) { return Files.exists(dir.resolve("package.json")); }
        @Override public List<String> compileCommand() { return List.of("npm", "--offline", "run", "build"); }
        @Override public List<String> testCommand()    { return List.of("npm", "--offline", "test"); }
    },
    PYTHON {
        @Override public boolean matches(Path dir) {
            return Files.exists(dir.resolve("pyproject.toml"))
                    || Files.exists(dir.resolve("requirements.txt"))
                    || Files.exists(dir.resolve("setup.py"));
        }
        @Override public List<String> compileCommand() { return List.of("python3", "-m", "compileall", "-q", "."); }
        @Override public List<String> testCommand()    { return List.of("python3", "-m", "pytest", "-q"); }
    },
    GO {
        @Override public boolean matches(Path dir) { return Files.exists(dir.resolve("go.mod")); }
        @Override public List<String> compileCommand() { return List.of("go", "build", "./..."); }
        @Override public List<String> testCommand()    { return List.of("go", "test", "./..."); }
    },
    RUST {
        @Override public boolean matches(Path dir) { return Files.exists(dir.resolve("Cargo.toml")); }
        @Override public List<String> compileCommand() { return List.of("cargo", "build", "--offline", "-q"); }
        @Override public List<String> testCommand()    { return List.of("cargo", "test", "--offline", "-q"); }
    };

    public abstract boolean matches(Path dir);

    public abstract List<String> compileCommand();

    public abstract List<String> testCommand();

    /** 优先用 wrapper（gradlew）以匹配项目锁定的 gradle 版本，否则回退系统 gradle。 */
    protected static String gradle() {
        return "./gradlew";
    }

    /** 探测目录用的构建体系；探测不到返回空（调用方须优雅回退，不得假装验证通过）。 */
    public static Optional<BuildTarget> detect(Path dir) {
        for (BuildTarget t : values()) {
            if (t.matches(dir)) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** 落库用的小写标识（maven/gradle/npm/python/go/rust）。 */
    public String code() {
        return name().toLowerCase();
    }
}
