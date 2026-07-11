package com.repolens.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VerificationOutputParserTest {

    @TempDir java.nio.file.Path tmp;

    private final VerificationOutputParser parser = new VerificationOutputParser();

    @Test
    void parses_maven_compile_error() {
        String output = """
                [INFO] Compiling 3 source files
                [ERROR] /home/dev/project/src/main/java/com/example/Foo.java:[42,8] error: cannot find symbol
                  symbol:   variable bar
                [ERROR] Build FAILED
                """;
        List<VerificationOutputParser.Failure> failures = parser.parse(output, null);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).line()).isEqualTo(42);
        assertThat(failures.get(0).message()).contains("cannot find symbol");
    }

    @Test
    void empty_output_returns_empty_list() {
        assertThat(parser.parse("", null)).isEmpty();
        assertThat(parser.parse(null, null)).isEmpty();
    }

    @Test
    void build_success_output_returns_empty_list() {
        String output = """
                [INFO] BUILD SUCCESS
                [INFO] Total time: 3.456 s
                """;
        assertThat(parser.parse(output, null)).isEmpty();
    }

    @Test
    void parses_junit_failure() {
        String output = """
                com.example.FooTest > testSomething FAILED
                    java.lang.AssertionError: expected true but was false
                        at org.junit.jupiter.api.Assertions.fail(Assertions.java:109)
                        at com.example.FooTest.testSomething(FooTest.java:23)
                """;
        List<VerificationOutputParser.Failure> failures = parser.parse(output, null);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).line()).isEqualTo(23);
        assertThat(failures.get(0).symbol()).contains("testSomething");
    }

    @Test
    void parser_exception_is_safe_returns_empty() {
        List<VerificationOutputParser.Failure> result = parser.parse("随机乱码\u0000\n\r", null);
        assertThat(result).isNotNull();
    }

    @Test
    void tscError_parsedCorrectly() {
        String output = "src/main/ts/Foo.ts(12,5): error TS2322: Type 'string' is not assignable to type 'number'.";
        var failures = parser.parse(output, tmp);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).file()).contains("Foo.ts");
        assertThat(failures.get(0).line()).isEqualTo(12);
        assertThat(failures.get(0).symbol()).isEqualTo("TS2322");
    }

    @Test
    void extractContext_populated_when_shadowDir_provided() throws Exception {
        java.nio.file.Path srcDir = tmp.resolve("src/main/java/com/example");
        java.nio.file.Files.createDirectories(srcDir);
        String code = """
                package com.example;

                public class Foo {
                    public void bar() {
                        int x = 1;
                        String y = null;
                        System.out.println(y.length()); // line 8: NPE
                    }

                    public void baz() {
                        return;
                    }
                }
                """;
        java.nio.file.Files.writeString(srcDir.resolve("Foo.java"), code);

        String output = "[ERROR] /home/dev/project/src/main/java/com/example/Foo.java:[8,20] error: NullPointerException";
        var failures = parser.parse(output, tmp);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).line()).isEqualTo(8);
        assertThat(failures.get(0).context()).isNotBlank();
        assertThat(failures.get(0).context()).contains("public void bar()");
        assertThat(failures.get(0).context()).contains("System.out.println");
    }
}
