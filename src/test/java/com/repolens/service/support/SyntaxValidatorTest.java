package com.repolens.service.support;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SyntaxValidatorTest {

    SyntaxValidator validator = new SyntaxValidator();

    @Test
    void validJava_passes() {
        String code = "public class Foo { public void bar() {} }";
        assertThat(validator.validate("Foo.java", code).valid()).isTrue();
    }

    @Test
    void invalidJava_fails() {
        String code = "public class Foo { public void bar( }";
        var result = validator.validate("Foo.java", code);
        assertThat(result.valid()).isFalse();
        assertThat(result.errorMessage()).isNotBlank();
    }

    @Test
    void nonJava_alwaysPasses() {
        assertThat(validator.validate("config.yml", "invalid: [yaml").valid()).isTrue();
        assertThat(validator.validate("README.md", "# broken ~~~").valid()).isTrue();
    }

    @Test
    void nullContent_doesNotThrow() {
        assertThat(validator.validate("Foo.java", null).valid()).isFalse();
    }

    @Test
    void nullPath_passes() {
        assertThat(validator.validate(null, "whatever").valid()).isTrue();
    }
}
