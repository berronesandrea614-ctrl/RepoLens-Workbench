package com.repolens.controller;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.entity.AiContributionRecordEntity;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.vo.ProvenanceRecordVO;
import com.repolens.domain.vo.ProvenanceVerifyVO;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.security.AuthUserId;
import com.repolens.security.PermissionService;
import com.repolens.service.ProvenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 贡献溯源审计接口（Feature F）。
 *
 * <ul>
 *   <li>GET  /api/repos/{repoId}/provenance/records        — 时间线（分页）</li>
 *   <li>GET  /api/repos/{repoId}/provenance/records/{id}   — 单条全链路详情（P1）</li>
 *   <li>GET  /api/repos/{repoId}/provenance/verify         — 重算哈希链完整性（P1）</li>
 *   <li>GET  /api/repos/{repoId}/provenance/export         — 导出 json/csv/aibom（P2）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/repos/{repoId}/provenance")
@RequiredArgsConstructor
public class ProvenanceController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProvenanceService provenanceService;
    private final PermissionService permissionService;
    private final AppSettingMapper appSettingMapper;

    // ─── P0: 时间线列表 ──────────────────────────────────────────────────────────

    /**
     * 分页查询 repo 溯源账本（最新在前）。
     * 返回 {records, total, page, size}。
     */
    @GetMapping("/records")
    public Result<Map<String, Object>> listRecords(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        ensureRepoPermission(userId, repoId);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        List<AiContributionRecordEntity> entities = provenanceService.listRecords(repoId, page, safeSize);
        long total = provenanceService.countRecords(repoId);
        List<ProvenanceRecordVO> records = entities.stream()
                .map(e -> toVO(e, true))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("page", page);
        result.put("size", safeSize);
        return Result.success(result);
    }

    // ─── P1: 单条全链路详情 ──────────────────────────────────────────────────────

    /**
     * 返回单条账本记录的全链路详情：
     * AI 侧（prompt 哈希、模型、context）+ 人工侧（批准人、时间、决策、diff 哈希）。
     */
    @GetMapping("/records/{id}")
    public Result<ProvenanceRecordVO> getRecord(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @PathVariable Long id) {

        ensureRepoPermission(userId, repoId);
        AiContributionRecordEntity entity = provenanceService.getRecord(id);
        if (entity == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Provenance record not found: " + id);
        }
        if (!repoId.equals(entity.getRepoId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "Record does not belong to repo " + repoId);
        }
        return Result.success(toVO(entity, false));
    }

    // ─── P1: 哈希链校验 ──────────────────────────────────────────────────────────

    /**
     * 重算 repo 账本哈希链，检测篡改。
     * 若任何记录的 record_hash 与重算值不符，返回 verified=false 及 brokenAtSeq。
     */
    @GetMapping("/verify")
    public Result<ProvenanceVerifyVO> verifyChain(
            @AuthUserId Long userId,
            @PathVariable Long repoId) {

        ensureRepoPermission(userId, repoId);
        ProvenanceService.VerifyResult result = provenanceService.verifyChain(repoId);
        long total = provenanceService.countRecords(repoId);

        ProvenanceVerifyVO vo = ProvenanceVerifyVO.builder()
                .verified(result.verified())
                .brokenAtSeq(result.brokenAtSeq())
                .totalRecords(total)
                .note(result.verified()
                        ? "账本哈希链完整，共 " + total + " 条记录无篡改"
                        : "检测到哈希链断裂，建议核查 seq=" + result.brokenAtSeq() + " 附近的记录")
                .build();
        return Result.success(vo);
    }

    // ─── P2: 导出 ────────────────────────────────────────────────────────────────

    /**
     * 导出溯源账本（P2）。
     * format=json — 所有记录的 JSON 数组（含 EU AI Act 条款注释）。
     * format=csv  — 所有记录的 CSV（可下载）。
     * format=aibom — CycloneDX ML-BOM JSON 片段（模型组件 + 溯源）。
     */
    @GetMapping("/export")
    public ResponseEntity<?> export(
            @AuthUserId Long userId,
            @PathVariable Long repoId,
            @RequestParam(value = "format", defaultValue = "json") String format) {

        ensureRepoPermission(userId, repoId);
        // Retrieve all records (cap at 10000 for export safety)
        List<AiContributionRecordEntity> entities = provenanceService.listRecords(repoId, 0, 10000);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        if ("csv".equalsIgnoreCase(format)) {
            String csv = buildCsv(entities);
            String filename = "repolens-provenance-" + repoId + "-" + ts + ".csv";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .body(csv);
        }

        if ("aibom".equalsIgnoreCase(format)) {
            Map<String, Object> aibom = buildAiBom(entities, repoId);
            String filename = "repolens-aibom-" + repoId + "-" + ts + ".json";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(aibom);
        }

        // Default: JSON
        List<Map<String, Object>> records = new ArrayList<>();
        for (AiContributionRecordEntity e : entities) {
            Map<String, Object> m = toExportMap(e);
            records.add(m);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("reportType", "RepoLens AI 贡献溯源账本");
        response.put("repoId", repoId);
        response.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        response.put("totalRecords", entities.size());
        response.put("records", records);
        response.put("complianceMapping", buildComplianceMapping());
        response.put("retentionDays", getRetentionDays());
        String filename = "repolens-provenance-" + repoId + "-" + ts + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.success(response));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────────

    private ProvenanceRecordVO toVO(AiContributionRecordEntity e, boolean abbreviated) {
        // For list view, show abbreviated prompt hash (first 8 chars for fingerprint column)
        return ProvenanceRecordVO.builder()
                .id(e.getId())
                .repoId(e.getRepoId())
                .seq(e.getSeq())
                .changeId(e.getChangeId())
                .llmCallId(e.getLlmCallId())
                .agentRunId(e.getAgentRunId())
                .provider(e.getProvider())
                .modelName(e.getModelName())
                .modelVersion(e.getModelVersion())
                .promptHash(e.getPromptHash())
                .contextHash(e.getContextHash())
                .filePath(e.getFilePath())
                .diffHash(e.getDiffHash())
                .decision(e.getDecision())
                .approverId(e.getApproverId())
                .decidedAt(e.getDecidedAt())
                .prevHash(abbreviated ? null : e.getPrevHash())
                .recordHash(e.getRecordHash())
                .complianceNote(buildComplianceNote(e))
                .build();
    }

    private String buildComplianceNote(AiContributionRecordEntity e) {
        return "EU AI Act Art.12(自动记录) · Art.19(≥6月留存) · Art.14(人类监督approver=" +
                (e.getApproverId() != null ? e.getApproverId() : "N/A") + ") · " +
                "model=" + (e.getModelName() != null ? e.getModelName() : "未知") +
                "(AI-BOM) · record_hash链(可追溯)";
    }

    private Map<String, Object> buildComplianceMapping() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("自动记录", "EU AI Act Art.12 / ISO 42001 A.6.2.8");
        m.put("留存≥6月", "EU AI Act Art.19（audit.retention-days 默认400天）");
        m.put("模型版本(model_version)", "CycloneDX ML-BOM / AI-BOM");
        m.put("批准人(approver_id)", "EU AI Act Art.14 人类监督义务");
        m.put("record_hash哈希链", "可追溯性 / NIST AI RMF GOVERN-1.1");
        return m;
    }

    private int getRetentionDays() {
        try {
            AppSettingEntity s = appSettingMapper.selectById("audit.retention-days");
            if (s != null && s.getV() != null) {
                return Integer.parseInt(s.getV());
            }
        } catch (Exception ex) {
            // fail-safe
        }
        return 400;
    }

    private Map<String, Object> toExportMap(AiContributionRecordEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.getSeq());
        m.put("decidedAt", e.getDecidedAt() != null ? e.getDecidedAt().toString() : null);
        m.put("filePath", e.getFilePath());
        m.put("decision", e.getDecision());
        m.put("approverId", e.getApproverId());
        m.put("modelName", e.getModelName());
        m.put("modelVersion", e.getModelVersion());
        m.put("provider", e.getProvider());
        m.put("promptHash", e.getPromptHash());
        m.put("contextHash", e.getContextHash());
        m.put("diffHash", e.getDiffHash());
        m.put("changeId", e.getChangeId());
        m.put("llmCallId", e.getLlmCallId());
        m.put("agentRunId", e.getAgentRunId());
        m.put("recordHash", e.getRecordHash());
        m.put("prevHash", e.getPrevHash());
        m.put("complianceNote", buildComplianceNote(e));
        return m;
    }

    private String buildCsv(List<AiContributionRecordEntity> entities) {
        StringBuilder sb = new StringBuilder();
        sb.append("seq,decidedAt,filePath,decision,approverId,modelName,modelVersion,provider,promptHash,diffHash,changeId,llmCallId,agentRunId,recordHash\n");
        for (AiContributionRecordEntity e : entities) {
            sb.append(csvCell(e.getSeq())).append(",")
              .append(csvCell(e.getDecidedAt())).append(",")
              .append(csvCell(e.getFilePath())).append(",")
              .append(csvCell(e.getDecision())).append(",")
              .append(csvCell(e.getApproverId())).append(",")
              .append(csvCell(e.getModelName())).append(",")
              .append(csvCell(e.getModelVersion())).append(",")
              .append(csvCell(e.getProvider())).append(",")
              .append(csvCell(e.getPromptHash())).append(",")
              .append(csvCell(e.getDiffHash())).append(",")
              .append(csvCell(e.getChangeId())).append(",")
              .append(csvCell(e.getLlmCallId())).append(",")
              .append(csvCell(e.getAgentRunId())).append(",")
              .append(csvCell(e.getRecordHash())).append("\n");
        }
        return sb.toString();
    }

    private String csvCell(Object value) {
        if (value == null) return "";
        String s = value.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    /**
     * 构建 CycloneDX 1.5 ML-BOM JSON 片段（aibom 格式）。
     * 仅生成 components（model components）+ metadata + externalReferences。
     * 不引入 CycloneDX SDK 依赖；手工构造合规片段。
     */
    private Map<String, Object> buildAiBom(List<AiContributionRecordEntity> entities, Long repoId) {
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("serialNumber", "urn:uuid:repolens-" + repoId + "-" + System.currentTimeMillis());
        bom.put("version", 1);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("vendor", "RepoLens");
        tool.put("name", "RepoLens AI Provenance Ledger");
        tool.put("version", "F-1.0");
        metadata.put("tools", List.of(Map.of("tool", tool)));
        bom.put("metadata", metadata);

        // Collect unique model components
        Map<String, Map<String, Object>> models = new LinkedHashMap<>();
        for (AiContributionRecordEntity e : entities) {
            if (e.getModelName() == null) continue;
            String key = (e.getProvider() != null ? e.getProvider() : "unknown") + ":" + e.getModelName();
            if (!models.containsKey(key)) {
                Map<String, Object> comp = new LinkedHashMap<>();
                comp.put("type", "machine-learning-model");
                comp.put("name", e.getModelName());
                comp.put("version", e.getModelVersion() != null ? e.getModelVersion() : e.getModelName());
                comp.put("supplier", Map.of("name", e.getProvider() != null ? e.getProvider() : "unknown"));
                models.put(key, comp);
            }
        }
        bom.put("components", new ArrayList<>(models.values()));

        // Provenance evidence (abbreviated)
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (AiContributionRecordEntity e : entities) {
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("seq", e.getSeq());
            ev.put("filePath", e.getFilePath());
            ev.put("decision", e.getDecision());
            ev.put("decidedAt", e.getDecidedAt() != null ? e.getDecidedAt().toString() : null);
            ev.put("approverId", e.getApproverId());
            ev.put("modelName", e.getModelName());
            ev.put("promptHash", e.getPromptHash());
            ev.put("recordHash", e.getRecordHash());
            evidence.add(ev);
        }
        bom.put("provenanceEvidence", evidence);

        bom.put("complianceNotes", Map.of(
                "EU_AI_Act_Art12", "AI system interactions logged automatically",
                "EU_AI_Act_Art14", "Human oversight via approver_id field",
                "EU_AI_Act_Art19", "Records retained ≥ 400 days (configurable via audit.retention-days)",
                "AI_BOM", "Model components listed in components section with version",
                "traceability", "Tamper-evident hash chain: record_hash = SHA-256(prev_hash|fields)"
        ));
        return bom;
    }

    private void ensureRepoPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }
}
