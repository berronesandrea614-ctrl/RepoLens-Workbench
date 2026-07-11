package com.repolens.controller;

import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.entity.AiContributionRecordEntity;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ProvenanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * standaloneSetup controller 单测 — Feature F ProvenanceController.
 *
 * 覆盖：
 * GET /api/repos/{repoId}/provenance/records          (P0 时间线分页)
 * GET /api/repos/{repoId}/provenance/records/{id}     (P1 单条详情)
 * GET /api/repos/{repoId}/provenance/verify           (P1 哈希链校验)
 * GET /api/repos/{repoId}/provenance/export?format=json   (P2 json 导出)
 * GET /api/repos/{repoId}/provenance/export?format=csv    (P2 csv 导出)
 * GET /api/repos/{repoId}/provenance/export?format=aibom  (P2 AI-BOM 导出)
 */
class ProvenanceControllerTest {

    private ProvenanceService provenanceService;
    private PermissionService permissionService;
    private AppSettingMapper appSettingMapper;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        provenanceService = mock(ProvenanceService.class);
        permissionService = mock(PermissionService.class);
        appSettingMapper = mock(AppSettingMapper.class);

        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(true);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProvenanceController(provenanceService, permissionService, appSettingMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(), new StringHttpMessageConverter())
                .build();
    }

    // ─── Helper ──────────────────────────────────────────────────────────────────

    private AiContributionRecordEntity makeRecord(long id, long repoId, long seq, String decision) {
        AiContributionRecordEntity e = new AiContributionRecordEntity();
        e.setId(id);
        e.setRepoId(repoId);
        e.setSeq(seq);
        e.setChangeId(100L + seq);
        e.setDecision(decision);
        e.setApproverId(1L);
        e.setDecidedAt(LocalDateTime.of(2026, 7, 5, 10, 0, 0));
        e.setFilePath("src/Foo.java");
        e.setModelName("deepseek-coder");
        e.setModelVersion("2.5");
        e.setProvider("deepseek");
        e.setPromptHash("aabbccdd");
        e.setDiffHash("ddeeff00");
        e.setRecordHash("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        e.setPrevHash("0000000000000000000000000000000000000000000000000000000000000000");
        return e;
    }

    // ─── GET /records ────────────────────────────────────────────────────────────

    @Test
    void listRecords_returnsPagedResult() throws Exception {
        AiContributionRecordEntity r = makeRecord(1L, 7L, 1L, "APPROVED");
        when(provenanceService.listRecords(eq(7L), eq(0), eq(20))).thenReturn(List.of(r));
        when(provenanceService.countRecords(7L)).thenReturn(1L);

        mockMvc.perform(get("/api/repos/7/provenance/records").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.records[0].decision").value("APPROVED"))
                .andExpect(jsonPath("$.data.records[0].modelName").value("deepseek-coder"))
                .andExpect(jsonPath("$.data.records[0].seq").value(1));
    }

    @Test
    void listRecords_emptySizeClamped() throws Exception {
        when(provenanceService.listRecords(eq(7L), eq(0), eq(1))).thenReturn(List.of());
        when(provenanceService.countRecords(7L)).thenReturn(0L);

        mockMvc.perform(get("/api/repos/7/provenance/records").param("size", "0").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1));   // clamped to min 1
    }

    @Test
    void listRecords_oversizedClamped() throws Exception {
        when(provenanceService.listRecords(eq(7L), eq(0), eq(100))).thenReturn(List.of());
        when(provenanceService.countRecords(7L)).thenReturn(0L);

        mockMvc.perform(get("/api/repos/7/provenance/records").param("size", "9999").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));  // clamped to max 100
    }

    @Test
    void listRecords_permissionDenied_returns403() throws Exception {
        when(permissionService.checkRepoPermission(anyLong(), anyLong())).thenReturn(false);

        mockMvc.perform(get("/api/repos/7/provenance/records").accept(APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ─── GET /records/{id} ───────────────────────────────────────────────────────

    @Test
    void getRecord_returnsDetailVO() throws Exception {
        AiContributionRecordEntity r = makeRecord(42L, 7L, 3L, "REJECTED");
        when(provenanceService.getRecord(42L)).thenReturn(r);

        mockMvc.perform(get("/api/repos/7/provenance/records/42").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.decision").value("REJECTED"))
                .andExpect(jsonPath("$.data.seq").value(3))
                .andExpect(jsonPath("$.data.recordHash").value("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void getRecord_notFound_returns404() throws Exception {
        when(provenanceService.getRecord(999L)).thenReturn(null);

        mockMvc.perform(get("/api/repos/7/provenance/records/999").accept(APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    void getRecord_wrongRepo_returns403() throws Exception {
        // Record belongs to repoId=99, but request is for repoId=7
        AiContributionRecordEntity r = makeRecord(5L, 99L, 1L, "APPROVED");
        when(provenanceService.getRecord(5L)).thenReturn(r);

        mockMvc.perform(get("/api/repos/7/provenance/records/5").accept(APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    // ─── GET /verify ─────────────────────────────────────────────────────────────

    @Test
    void verify_verifiedChain_returnsVerifiedTrue() throws Exception {
        when(provenanceService.verifyChain(7L)).thenReturn(ProvenanceService.VerifyResult.ok());
        when(provenanceService.countRecords(7L)).thenReturn(5L);

        mockMvc.perform(get("/api/repos/7/provenance/verify").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.totalRecords").value(5))
                .andExpect(jsonPath("$.data.brokenAtSeq").isEmpty());
    }

    @Test
    void verify_tamperedChain_returnsVerifiedFalse() throws Exception {
        when(provenanceService.verifyChain(7L)).thenReturn(ProvenanceService.VerifyResult.broken(3L));
        when(provenanceService.countRecords(7L)).thenReturn(5L);

        mockMvc.perform(get("/api/repos/7/provenance/verify").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.brokenAtSeq").value(3));
    }

    // ─── GET /export ─────────────────────────────────────────────────────────────

    @Test
    void export_jsonFormat_returnsJsonWithComplianceMapping() throws Exception {
        AiContributionRecordEntity r = makeRecord(1L, 7L, 1L, "APPROVED");
        when(provenanceService.listRecords(eq(7L), eq(0), eq(10000))).thenReturn(List.of(r));
        when(appSettingMapper.selectById(anyString())).thenReturn(null);

        mockMvc.perform(get("/api/repos/7/provenance/export")
                        .param("format", "json")
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.data.reportType").value("RepoLens AI 贡献溯源账本"))
                .andExpect(jsonPath("$.data.repoId").value(7))
                .andExpect(jsonPath("$.data.totalRecords").value(1))
                .andExpect(jsonPath("$.data.records[0].decision").value("APPROVED"))
                .andExpect(jsonPath("$.data.complianceMapping").exists());
    }

    @Test
    void export_csvFormat_returnsCsvWithHeader() throws Exception {
        AiContributionRecordEntity r = makeRecord(1L, 7L, 1L, "APPROVED");
        when(provenanceService.listRecords(eq(7L), eq(0), eq(10000))).thenReturn(List.of(r));

        mockMvc.perform(get("/api/repos/7/provenance/export").param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("seq,decidedAt,filePath")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("APPROVED")));
    }

    @Test
    void export_aibomFormat_returnsCycloneDxBom() throws Exception {
        AiContributionRecordEntity r = makeRecord(1L, 7L, 1L, "APPROVED");
        when(provenanceService.listRecords(eq(7L), eq(0), eq(10000))).thenReturn(List.of(r));

        mockMvc.perform(get("/api/repos/7/provenance/export")
                        .param("format", "aibom")
                        .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(jsonPath("$.bomFormat").value("CycloneDX"))
                .andExpect(jsonPath("$.specVersion").value("1.5"))
                .andExpect(jsonPath("$.components").isArray())
                .andExpect(jsonPath("$.provenanceEvidence").isArray())
                .andExpect(jsonPath("$.complianceNotes.EU_AI_Act_Art12").exists());
    }

    @Test
    void export_defaultFormat_isJson() throws Exception {
        when(provenanceService.listRecords(eq(7L), eq(0), eq(10000))).thenReturn(List.of());
        when(appSettingMapper.selectById(anyString())).thenReturn(null);

        // No format param — should default to JSON
        mockMvc.perform(get("/api/repos/7/provenance/export").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportType").value("RepoLens AI 贡献溯源账本"));
    }
}
