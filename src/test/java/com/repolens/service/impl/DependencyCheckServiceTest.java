package com.repolens.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.config.DependencyCheckProperties;
import com.repolens.domain.entity.DependencyCheckEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.vo.DependencyCheckVO;
import com.repolens.mapper.DependencyCheckMapper;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.service.impl.support.ExtractedDep;
import com.repolens.service.impl.support.JavaDependencyExtractor;
import com.repolens.service.impl.support.JsDependencyExtractor;
import com.repolens.service.impl.support.OfflineSnapshotLoader;
import com.repolens.service.impl.support.OsvClient;
import com.repolens.service.impl.support.PythonDependencyExtractor;
import com.repolens.service.impl.support.RegistryClient;
import com.repolens.service.impl.support.RegistryCacheService;
import com.repolens.service.impl.support.TyposquatDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DependencyCheckServiceImpl unit tests (mock all HTTP — no real network).
 * Uses standaloneSetup (no Spring context).
 */
class DependencyCheckServiceTest {

    private FileChangeLogMapper fileChangeLogMapper;
    private DependencyCheckMapper dependencyCheckMapper;
    private JsDependencyExtractor jsExtractor;
    private PythonDependencyExtractor pyExtractor;
    private JavaDependencyExtractor javaExtractor;
    private RegistryClient registryClient;
    private TyposquatDetector typosquatDetector;
    private OsvClient osvClient;
    private RegistryCacheService registryCacheService;
    private DependencyCheckProperties depcheckProperties;
    private OfflineSnapshotLoader offlineSnapshotLoader;
    private DependencyCheckServiceImpl service;

    @BeforeEach
    void setup() {
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        dependencyCheckMapper = mock(DependencyCheckMapper.class);
        jsExtractor = mock(JsDependencyExtractor.class);
        pyExtractor = mock(PythonDependencyExtractor.class);
        javaExtractor = mock(JavaDependencyExtractor.class);
        registryClient = mock(RegistryClient.class);
        typosquatDetector = mock(TyposquatDetector.class);
        osvClient = mock(OsvClient.class);
        registryCacheService = mock(RegistryCacheService.class);
        depcheckProperties = new DependencyCheckProperties();
        depcheckProperties.setMode(DependencyCheckProperties.Mode.ONLINE);
        offlineSnapshotLoader = mock(OfflineSnapshotLoader.class);

        service = new DependencyCheckServiceImpl(
                fileChangeLogMapper, dependencyCheckMapper,
                jsExtractor, pyExtractor, javaExtractor,
                registryClient, typosquatDetector,
                osvClient, registryCacheService, depcheckProperties, offlineSnapshotLoader,
                new ObjectMapper());

        // Default: extractors handle nothing; typosquat finds nothing; no offline malicious
        when(jsExtractor.supports(anyString())).thenReturn(false);
        when(pyExtractor.supports(anyString())).thenReturn(false);
        when(javaExtractor.supports(anyString())).thenReturn(false);
        when(typosquatDetector.detectNpm(anyString())).thenReturn(Optional.empty());
        when(typosquatDetector.detectPypi(anyString())).thenReturn(Optional.empty());
        when(offlineSnapshotLoader.isMaliciousOffline(anyString(), anyString())).thenReturn(false);
        // OSV batch returns empty map (no hits)
        when(osvClient.queryBatch(anyList())).thenReturn(Map.of());
        // Cache miss by default
        when(registryCacheService.get(anyString(), anyString())).thenReturn(Optional.empty());
    }

    // ─────────────────────── helper ──────────────────────────────────────────

    private FileChangeLogEntity changeFor(long id, String filePath, String oldContent, String newContent) {
        FileChangeLogEntity e = new FileChangeLogEntity();
        e.setId(id);
        e.setRepoId(1L);
        e.setSessionId(10L);
        e.setFilePath(filePath);
        e.setOldContent(oldContent);
        e.setNewContent(newContent);
        return e;
    }

    private ExtractedDep pyDep(String name, String version) {
        return new ExtractedDep("pypi", name, version, "MANIFEST", "requirements.txt", null);
    }

    // ─────────────────── verdict priority tests ──────────────────────────────

    @Test
    void typosquat_hasHigherPriorityThanNotFound() {
        FileChangeLogEntity change = changeFor(1L, "requirements.txt", "", "requets==1.0\n");
        when(fileChangeLogMapper.selectById(eq(1L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("requets", "1.0")));
        when(typosquatDetector.detectPypi(eq("requets"))).thenReturn(Optional.of("requests"));

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(1L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_TYPOSQUAT);
        // RegistryClient must NOT be called because TYPOSQUAT is already decisive
        verify(registryClient, never()).checkNpm(anyString());
        verify(registryClient, never()).checkPypi(anyString());
    }

    @Test
    void notFound_whenRegistryReturns404() {
        FileChangeLogEntity change = changeFor(2L, "requirements.txt", "", "fakepkg==0.1\n");
        when(fileChangeLogMapper.selectById(eq(2L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("fakepkg", "0.1")));
        when(registryClient.checkPypi(eq("fakepkg"))).thenReturn(DependencyCheckEntity.VERDICT_NOT_FOUND);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(2L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_NOT_FOUND);
    }

    @Test
    void ok_whenRegistryReturns200() {
        FileChangeLogEntity change = changeFor(3L, "requirements.txt", "", "requests==2.28.0\n");
        when(fileChangeLogMapper.selectById(eq(3L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("requests", "2.28.0")));
        when(registryClient.checkPypi(eq("requests"))).thenReturn(DependencyCheckEntity.VERDICT_OK);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(3L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_OK);
    }

    @Test
    void unknown_whenRegistryClientReturnsUnknown() {
        FileChangeLogEntity change = changeFor(4L, "requirements.txt", "", "somepkg==1.0\n");
        when(fileChangeLogMapper.selectById(eq(4L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("somepkg", "1.0")));
        when(registryClient.checkPypi(anyString())).thenReturn(DependencyCheckEntity.VERDICT_UNKNOWN);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(4L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_UNKNOWN);
    }

    // ─────────────────── MALICIOUS / OSV tests ───────────────────────────────

    @Test
    void osv_malicious_verdict_returned() {
        FileChangeLogEntity change = changeFor(10L, "requirements.txt", "", "evil-pkg==1.0\n");
        when(fileChangeLogMapper.selectById(eq(10L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        ExtractedDep dep = pyDep("evil-pkg", "1.0");
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString())).thenReturn(List.of(dep));
        // OSV batch returns MALICIOUS for this dep
        when(osvClient.queryBatch(anyList())).thenReturn(Map.of("pypi:evil-pkg",
                DependencyCheckEntity.VERDICT_MALICIOUS));
        when(registryClient.checkPypi(anyString())).thenReturn(DependencyCheckEntity.VERDICT_OK);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(10L));

        assertThat(results).hasSize(1);
        // MALICIOUS(1) beats OK(5)
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_MALICIOUS);
    }

    @Test
    void osv_vulnerable_verdict_returned() {
        FileChangeLogEntity change = changeFor(11L, "requirements.txt", "", "vuln-pkg==1.0\n");
        when(fileChangeLogMapper.selectById(eq(11L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        ExtractedDep dep = pyDep("vuln-pkg", "1.0");
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString())).thenReturn(List.of(dep));
        when(osvClient.queryBatch(anyList())).thenReturn(Map.of("pypi:vuln-pkg",
                DependencyCheckEntity.VERDICT_VULNERABLE));
        when(registryClient.checkPypi(anyString())).thenReturn(DependencyCheckEntity.VERDICT_OK);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(11L));

        assertThat(results).hasSize(1);
        // OK(5) vs VULNERABLE(4): VULNERABLE wins
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_VULNERABLE);
    }

    // ─────────────────── OFFLINE mode tests ──────────────────────────────────

    @Test
    void offline_mode_skips_registry_and_sets_checkedOffline_flag() {
        depcheckProperties.setMode(DependencyCheckProperties.Mode.OFFLINE);
        FileChangeLogEntity change = changeFor(20L, "requirements.txt", "", "requests==2.28.0\n");
        when(fileChangeLogMapper.selectById(eq(20L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("requests", "2.28.0")));

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(20L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isCheckedOffline()).isTrue();
        // Registry and OSV must NOT be called in OFFLINE mode
        verify(registryClient, never()).checkPypi(anyString());
        verify(osvClient, never()).queryBatch(anyList());
    }

    @Test
    void offline_mode_returns_malicious_from_snapshot() {
        depcheckProperties.setMode(DependencyCheckProperties.Mode.OFFLINE);
        FileChangeLogEntity change = changeFor(21L, "requirements.txt", "", "ctx==0.1.2\n");
        when(fileChangeLogMapper.selectById(eq(21L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("ctx", "0.1.2")));
        // Offline snapshot flags this package
        when(offlineSnapshotLoader.isMaliciousOffline(eq("pypi"), eq("ctx"))).thenReturn(true);

        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of(21L));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getVerdict()).isEqualTo(DependencyCheckEntity.VERDICT_MALICIOUS);
        assertThat(results.get(0).isCheckedOffline()).isTrue();
    }

    // ─────────────────── edge cases ──────────────────────────────────────────

    @Test
    void checkByChangeIds_emptyList_returnsEmpty() {
        List<DependencyCheckVO> results = service.checkByChangeIds(1L, 10L, List.of());
        assertThat(results).isEmpty();
        verify(fileChangeLogMapper, never()).selectById(any());
    }

    @Test
    void checkByChangeIds_nullList_returnsEmpty() {
        assertThat(service.checkByChangeIds(1L, 10L, null)).isEmpty();
    }

    @Test
    void queryBySession_nullSessionId_returnsEmpty() {
        assertThat(service.queryBySession(1L, null)).isEmpty();
    }

    @Test
    void checkByChangeIds_changeNotFound_skipped() {
        when(fileChangeLogMapper.selectById(eq(99L))).thenReturn(null);
        assertThat(service.checkByChangeIds(1L, 10L, List.of(99L))).isEmpty();
    }

    @Test
    void checkByChangeIds_repoIdMismatch_skipped() {
        FileChangeLogEntity change = changeFor(5L, "requirements.txt", "", "pandas==1.5.0\n");
        change.setRepoId(999L);
        when(fileChangeLogMapper.selectById(eq(5L))).thenReturn(change);
        assertThat(service.checkByChangeIds(1L, 10L, List.of(5L))).isEmpty();
    }

    @Test
    void triggerAsyncCheck_submitsAndDoesNotBlock() throws Exception {
        assertThat(service).isNotNull();
        service.triggerAsyncCheck(1L, 10L, 77L);
        Thread.sleep(50);
    }

    @Test
    void recheck_sameChangeId_deletesBeforeInsertEachTime() {
        FileChangeLogEntity change = changeFor(1L, "requirements.txt", "", "requets==1.0\n");
        when(fileChangeLogMapper.selectById(eq(1L))).thenReturn(change);
        when(pyExtractor.supports(eq("requirements.txt"))).thenReturn(true);
        when(pyExtractor.extractAdded(anyString(), anyString(), anyString()))
                .thenReturn(List.of(pyDep("requets", "1.0")));
        when(typosquatDetector.detectPypi(eq("requets"))).thenReturn(Optional.of("requests"));

        service.checkByChangeIds(1L, 10L, List.of(1L));
        service.checkByChangeIds(1L, 10L, List.of(1L));

        verify(dependencyCheckMapper, times(2)).deleteByChangeId(eq(1L));
        verify(dependencyCheckMapper, times(2)).insert(any(DependencyCheckEntity.class));
    }
}
