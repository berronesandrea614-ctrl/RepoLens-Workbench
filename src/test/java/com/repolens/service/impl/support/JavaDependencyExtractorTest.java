package com.repolens.service.impl.support;

import com.repolens.config.DependencyCheckProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JavaDependencyExtractor 单测。
 * 纯函数测试，无 Spring 上下文，无网络。
 */
class JavaDependencyExtractorTest {

    private JavaDependencyExtractor extractor;

    @BeforeEach
    void setup() {
        DependencyCheckProperties props = new DependencyCheckProperties();
        props.setJavaBasePackage("com.repolens");
        extractor = new JavaDependencyExtractor(props);
    }

    // ───────────────────────────── supports ──────────────────────────────────

    @Test
    void supports_pom_xml() {
        assertThat(extractor.supports("pom.xml")).isTrue();
        assertThat(extractor.supports("project/pom.xml")).isTrue();
    }

    @Test
    void supports_build_gradle() {
        assertThat(extractor.supports("build.gradle")).isTrue();
        assertThat(extractor.supports("app/build.gradle.kts")).isTrue();
    }

    @Test
    void supports_java_source() {
        assertThat(extractor.supports("src/main/java/Foo.java")).isTrue();
    }

    @Test
    void supports_rejects_other_files() {
        assertThat(extractor.supports("package.json")).isFalse();
        assertThat(extractor.supports("requirements.txt")).isFalse();
        assertThat(extractor.supports(null)).isFalse();
    }

    // ─────────────────────────────── pom.xml ─────────────────────────────────

    @Test
    void pom_extractAdded_returns_new_deps() {
        String oldPom = "<project><dependencies>"
                + "<dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId><version>5.0</version></dependency>"
                + "</dependencies></project>";
        String newPom = "<project><dependencies>"
                + "<dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId><version>5.0</version></dependency>"
                + "<dependency><groupId>com.google.guava</groupId><artifactId>guava</artifactId><version>31.0-jre</version></dependency>"
                + "</dependencies></project>";

        List<ExtractedDep> deps = extractor.extractAdded("pom.xml", oldPom, newPom);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("com.google.guava:guava");
        assertThat(deps.get(0).version()).isEqualTo("31.0-jre");
        assertThat(deps.get(0).ecosystem()).isEqualTo(ExtractedDep.ECOSYSTEM_MAVEN);
        assertThat(deps.get(0).source()).isEqualTo(ExtractedDep.SOURCE_MANIFEST);
    }

    @Test
    void pom_extractAdded_empty_old_returns_all() {
        String newPom = "<project><dependencies>"
                + "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.12</version></dependency>"
                + "</dependencies></project>";

        List<ExtractedDep> deps = extractor.extractAdded("pom.xml", "", newPom);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("org.apache.commons:commons-lang3");
    }

    @Test
    void pom_extractAdded_no_change_returns_empty() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>"
                + "</dependencies></project>";

        assertThat(extractor.extractAdded("pom.xml", pom, pom)).isEmpty();
    }

    @Test
    void pom_malformed_xml_returns_empty() {
        assertThat(extractor.extractAdded("pom.xml", "", "NOT XML")).isEmpty();
    }

    // ─────────────────────────── build.gradle ────────────────────────────────

    @Test
    void gradle_extractAdded_returns_new_deps() {
        String oldGradle = "dependencies {\n"
                + "    implementation 'org.springframework:spring-core:5.0'\n"
                + "}\n";
        String newGradle = "dependencies {\n"
                + "    implementation 'org.springframework:spring-core:5.0'\n"
                + "    implementation 'com.google.guava:guava:31.0-jre'\n"
                + "}\n";

        List<ExtractedDep> deps = extractor.extractAdded("build.gradle", oldGradle, newGradle);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("com.google.guava:guava");
        assertThat(deps.get(0).version()).isEqualTo("31.0-jre");
        assertThat(deps.get(0).ecosystem()).isEqualTo(ExtractedDep.ECOSYSTEM_MAVEN);
    }

    @Test
    void gradle_extractAdded_kotlin_dsl_parens() {
        String oldGradle = "";
        String newGradle = "dependencies {\n"
                + "    testImplementation(\"org.junit.jupiter:junit-jupiter:5.9.0\")\n"
                + "}\n";

        List<ExtractedDep> deps = extractor.extractAdded("build.gradle.kts", oldGradle, newGradle);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("org.junit.jupiter:junit-jupiter");
        assertThat(deps.get(0).version()).isEqualTo("5.9.0");
    }

    @Test
    void gradle_extractAdded_no_change_returns_empty() {
        String gradle = "dependencies { implementation 'org.slf4j:slf4j-api:1.7.36' }\n";
        assertThat(extractor.extractAdded("build.gradle", gradle, gradle)).isEmpty();
    }

    // ───────────────────────────── .java source ──────────────────────────────

    @Test
    void java_extractAdded_returns_new_external_imports() {
        String oldJava = "package com.example;\n"
                + "import com.google.common.collect.ImmutableList;\n"
                + "public class Foo {}\n";
        String newJava = "package com.example;\n"
                + "import com.google.common.collect.ImmutableList;\n"
                + "import org.apache.commons.lang3.StringUtils;\n"
                + "public class Foo {}\n";

        List<ExtractedDep> deps = extractor.extractAdded("src/Foo.java", oldJava, newJava);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("org.apache.commons.lang3.StringUtils");
        assertThat(deps.get(0).ecosystem()).isEqualTo(ExtractedDep.ECOSYSTEM_MAVEN);
        assertThat(deps.get(0).source()).isEqualTo(ExtractedDep.SOURCE_IMPORT);
    }

    @Test
    void java_extractAdded_filters_jdk_imports() {
        String oldJava = "";
        String newJava = "package com.example;\n"
                + "import java.util.List;\n"
                + "import javax.annotation.Nullable;\n"
                + "import jdk.internal.misc.Unsafe;\n"
                + "import sun.misc.BASE64Encoder;\n"
                + "public class Foo {}\n";

        List<ExtractedDep> deps = extractor.extractAdded("src/Foo.java", oldJava, newJava);
        assertThat(deps).isEmpty();
    }

    @Test
    void java_extractAdded_filters_base_package() {
        String oldJava = "";
        String newJava = "package com.example;\n"
                + "import com.repolens.domain.entity.FooEntity;\n"
                + "import com.repolens.service.FooService;\n"
                + "public class Bar {}\n";

        List<ExtractedDep> deps = extractor.extractAdded("src/Bar.java", oldJava, newJava);
        // Both are in com.repolens base package → filtered out
        assertThat(deps).isEmpty();
    }

    @Test
    void java_extractAdded_empty_old_returns_external_imports() {
        String newJava = "package com.example;\n"
                + "import com.fasterxml.jackson.databind.ObjectMapper;\n"
                + "public class Baz {}\n";

        List<ExtractedDep> deps = extractor.extractAdded("Baz.java", "", newJava);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).name()).isEqualTo("com.fasterxml.jackson.databind.ObjectMapper");
    }

    @Test
    void java_parseImports_wildcard_import_included() {
        String java = "package com.example;\nimport org.springframework.web.bind.annotation.*;\npublic class C {}\n";
        Set<String> imports = extractor.parseJavaImports(java);
        assertThat(imports).contains("org.springframework.web.bind.annotation.*");
    }

    @Test
    void java_parseImports_empty_content_returns_empty() {
        assertThat(extractor.parseJavaImports("")).isEmpty();
        assertThat(extractor.parseJavaImports(null)).isEmpty();
    }

    @Test
    void java_parseImports_invalid_java_returns_empty() {
        // JavaParser should still parse partial code; but totally broken content returns empty
        assertThat(extractor.parseJavaImports("NOT JAVA !!@#$%")).isEmpty();
    }

    // ─────────────────────────── extractAdded null guards ────────────────────

    @Test
    void extractAdded_null_filePath_returns_empty() {
        assertThat(extractor.extractAdded(null, "", "content")).isEmpty();
    }

    @Test
    void extractAdded_null_newContent_returns_empty() {
        assertThat(extractor.extractAdded("pom.xml", "", null)).isEmpty();
    }
}
