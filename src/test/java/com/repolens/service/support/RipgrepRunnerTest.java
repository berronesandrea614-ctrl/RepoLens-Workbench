package com.repolens.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

import static org.assertj.core.api.Assertions.assertThat;

class RipgrepRunnerTest {

    @TempDir Path tmp;
    final RipgrepRunner runner = new RipgrepRunner();

    @Test
    void contextMode_findsMatch() throws Exception {
        Files.writeString(tmp.resolve("Foo.java"), "public class Foo { void bar() {} }");
        var result = runner.grep("bar", tmp, null, false, RipgrepRunner.Mode.CONTEXT);
        assertThat(result.output()).contains("bar");
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void filesMode_returnsFilename() throws Exception {
        Files.writeString(tmp.resolve("A.java"), "hello world");
        var result = runner.grep("hello", tmp, "*.java", false, RipgrepRunner.Mode.FILES_WITH_MATCHES);
        assertThat(result.output()).contains("A.java");
    }

    @Test
    void caseInsensitive_works() throws Exception {
        Files.writeString(tmp.resolve("B.java"), "UPPER lower");
        var result = runner.grep("upper", tmp, null, true, RipgrepRunner.Mode.CONTEXT);
        assertThat(result.output()).contains("UPPER");
    }

    @Test
    void skipsNodeModules() throws Exception {
        Path nm = tmp.resolve("node_modules");
        Files.createDirectories(nm);
        Files.writeString(nm.resolve("lib.js"), "needle");
        Files.writeString(tmp.resolve("src.js"), "no match here");
        var result = runner.grep("needle", tmp, null, false, RipgrepRunner.Mode.FILES_WITH_MATCHES);
        assertThat(result.output()).doesNotContain("node_modules");
    }
}
