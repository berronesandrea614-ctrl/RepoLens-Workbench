package com.repolens.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;

class FeatureLedgerServiceTest {

    @TempDir Path tmp;
    FeatureLedgerService service = new FeatureLedgerService(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void noFile_returnsEmpty() {
        assertThat(service.load(tmp)).isEmpty();
        assertThat(service.hasUnfinishedFeatures(tmp)).isFalse();
    }

    @Test
    void reconcile_marksFeatureDone() throws Exception {
        Path repolens = tmp.resolve(".repolens");
        Files.createDirectories(repolens);
        String json = "[{\"id\":\"f1\",\"description\":\"test\",\"status\":\"failing\",\"oraclePath\":\"src/test/FooOracle.java\"}]";
        Files.writeString(repolens.resolve("features.json"), json);

        service.reconcile(tmp, "src/test/FooOracle.java");

        var features = service.load(tmp);
        assertThat(features).hasSize(1);
        assertThat(features.get(0).status()).isEqualTo("done");
    }

    @Test
    void hasUnfinishedFeatures_true_whenFailing() throws Exception {
        Path repolens = tmp.resolve(".repolens");
        Files.createDirectories(repolens);
        Files.writeString(repolens.resolve("features.json"),
                "[{\"id\":\"f1\",\"description\":\"x\",\"status\":\"failing\"}]");
        assertThat(service.hasUnfinishedFeatures(tmp)).isTrue();
    }
}
