package com.repolens.service.impl.support;

import com.repolens.domain.enums.SymbolType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TraceLinkerTest {

    // ─── mergeAndDedup ────────────────────────────────────────────────────────

    @Test
    void mergeDedup_sameReqSymbol_keepsHigherConfidence() {
        var a = new TraceLinker.LinkCandidate(1L, 10L, "Foo.java", "RAG", 0.6);
        var b = new TraceLinker.LinkCandidate(1L, 10L, "Foo.java", "DECLARED", 1.0);
        List<TraceLinker.LinkCandidate> merged = TraceLinker.mergeAndDedup(List.of(a, b));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).confidence()).isEqualTo(1.0);
        assertThat(merged.get(0).linkType()).isEqualTo("DECLARED");
    }

    @Test
    void mergeDedup_sameConfidence_prefersDeclared() {
        var rag      = new TraceLinker.LinkCandidate(1L, 10L, "Foo.java", "RAG", 1.0);
        var declared = new TraceLinker.LinkCandidate(1L, 10L, "Foo.java", "DECLARED", 1.0);
        List<TraceLinker.LinkCandidate> merged = TraceLinker.mergeAndDedup(List.of(rag, declared));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).linkType()).isEqualTo("DECLARED");
    }

    @Test
    void mergeDedup_differentSymbols_keepsAll() {
        var a = new TraceLinker.LinkCandidate(1L, 10L, "Foo.java", "DECLARED", 1.0);
        var b = new TraceLinker.LinkCandidate(1L, 20L, "Bar.java", "RAG", 0.7);
        List<TraceLinker.LinkCandidate> merged = TraceLinker.mergeAndDedup(List.of(a, b));
        assertThat(merged).hasSize(2);
    }

    @Test
    void mergeDedup_capAt150() {
        List<TraceLinker.LinkCandidate> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new TraceLinker.LinkCandidate(1L, (long) i, "f" + i, "RAG", 0.5));
        }
        List<TraceLinker.LinkCandidate> merged = TraceLinker.mergeAndDedup(many);
        assertThat(merged.size()).isLessThanOrEqualTo(150);
    }

    // ─── inferLayer ───────────────────────────────────────────────────────────

    @Test
    void inferLayer_controller_returnsController() {
        assertThat(TraceLinker.inferLayer(SymbolType.CONTROLLER)).isEqualTo("Controller");
        assertThat(TraceLinker.inferLayer(SymbolType.API)).isEqualTo("Controller");
    }

    @Test
    void inferLayer_service_returnsService() {
        assertThat(TraceLinker.inferLayer(SymbolType.SERVICE)).isEqualTo("Service");
        assertThat(TraceLinker.inferLayer(SymbolType.METHOD)).isEqualTo("Service");
    }

    @Test
    void inferLayer_mapper_returnsMapper() {
        assertThat(TraceLinker.inferLayer(SymbolType.MAPPER)).isEqualTo("Mapper");
    }

    @Test
    void inferLayer_entity_returnsEntity() {
        assertThat(TraceLinker.inferLayer(SymbolType.ENTITY)).isEqualTo("Entity");
        assertThat(TraceLinker.inferLayer(SymbolType.CLASS)).isEqualTo("Entity");
    }

    @Test
    void inferLayer_config_returnsNull() {
        assertThat(TraceLinker.inferLayer(SymbolType.CONFIG)).isNull();
    }

    @Test
    void inferLayer_null_returnsNull() {
        assertThat(TraceLinker.inferLayer(null)).isNull();
    }

    // ─── isCoreLayer ─────────────────────────────────────────────────────────

    @Test
    void isCoreLayer_coreTypes_true() {
        assertThat(TraceLinker.isCoreLayer(SymbolType.CONTROLLER)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.SERVICE)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.MAPPER)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.ENTITY)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.API)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.METHOD)).isTrue();
        assertThat(TraceLinker.isCoreLayer(SymbolType.CLASS)).isTrue();
    }

    @Test
    void isCoreLayer_config_false() {
        assertThat(TraceLinker.isCoreLayer(SymbolType.CONFIG)).isFalse();
    }

    @Test
    void isCoreLayer_null_false() {
        assertThat(TraceLinker.isCoreLayer(null)).isFalse();
    }

    // ─── computeCoverage ─────────────────────────────────────────────────────

    @Test
    void computeCoverage_allLinked_returnsOne() {
        assertThat(TraceLinker.computeCoverage(
                Set.of(1L, 2L, 3L), Set.of(1L, 2L, 3L)))
                .isEqualTo(1.0);
    }

    @Test
    void computeCoverage_none_returnsZero() {
        assertThat(TraceLinker.computeCoverage(
                Set.of(1L, 2L), Set.of()))
                .isEqualTo(0.0);
    }

    @Test
    void computeCoverage_partial_returnsRatio() {
        double cov = TraceLinker.computeCoverage(
                Set.of(1L, 2L, 3L, 4L), Set.of(1L, 2L));
        assertThat(cov).isEqualTo(0.5);
    }

    @Test
    void computeCoverage_empty_returnsOne() {
        assertThat(TraceLinker.computeCoverage(Set.of(), Set.of()))
                .isEqualTo(1.0);
    }
}
