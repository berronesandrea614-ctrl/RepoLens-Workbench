package com.repolens.service.impl;

import com.repolens.common.util.HashUtils;
import com.repolens.domain.entity.AiContributionRecordEntity;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.LlmCallLogEntity;
import com.repolens.mapper.AiContributionRecordMapper;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.LlmCallLogMapper;
import com.repolens.service.ProvenanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 贡献溯源账本服务实现（Feature F）。
 *
 * 哈希链公式：
 *   record_hash = SHA-256(prevHash | repoId | seq | changeId | decision | approverId | decidedAt)
 *
 * 所有写入均 FAILURE-SAFE：任何异常静默 warn-log，不影响主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProvenanceServiceImpl implements ProvenanceService {

    /** app_setting key: 审计明文开关（默认 false = 只存哈希，隐私红线）。 */
    static final String KEY_CAPTURE_PLAINTEXT = "audit.capture-plaintext";
    /** app_setting key: 审计留存天数（默认 400，对齐 EU AI Act Art.19 ≥ 6月）。 */
    static final String KEY_RETENTION_DAYS = "audit.retention-days";

    private final AiContributionRecordMapper recordMapper;
    private final AppSettingMapper appSettingMapper;
    private final LlmCallLogMapper llmCallLogMapper;

    @Override
    public void appendRecordSafe(Long repoId, Long changeId, String filePath,
                                 String oldContent, String newContent,
                                 Long llmCallId, Long agentRunId,
                                 String decision, Long approverId) {
        try {
            doAppendRecord(repoId, changeId, filePath, oldContent, newContent,
                    llmCallId, agentRunId, decision, approverId);
        } catch (Exception ex) {
            log.warn("provenance: appendRecord failed (non-fatal), repoId={}, changeId={}, err={}",
                    repoId, changeId, ex.getMessage());
        }
    }

    private void doAppendRecord(Long repoId, Long changeId, String filePath,
                                String oldContent, String newContent,
                                Long llmCallId, Long agentRunId,
                                String decision, Long approverId) {
        LocalDateTime decidedAt = LocalDateTime.now();

        // 1. 查询当前最大 seq，得到下一个 seq
        Long maxSeq = recordMapper.selectMaxSeqByRepoId(repoId);
        long nextSeq = (maxSeq == null ? 0L : maxSeq) + 1L;

        // 2. 获取前一条记录的 record_hash（用于链接，genesis 时使用 GENESIS_HASH）
        String prevHash = nextSeq == 1L
                ? AiContributionRecordEntity.GENESIS_HASH
                : recordMapper.selectLastRecordHash(repoId);
        if (prevHash == null || prevHash.isBlank()) {
            prevHash = AiContributionRecordEntity.GENESIS_HASH;
        }

        // 3. 从 llm_call_log 获取模型信息（失败安全）
        String provider = null;
        String modelName = null;
        String modelVersion = null;
        String promptHash = null;
        String contextHash = null;
        if (llmCallId != null) {
            try {
                LlmCallLogEntity llmLog = llmCallLogMapper.selectById(llmCallId);
                if (llmLog != null) {
                    provider = llmLog.getProvider();
                    modelName = llmLog.getModelName();
                    modelVersion = llmLog.getModelVersion();
                    promptHash = llmLog.getPromptHash();
                    contextHash = llmLog.getContextHash();
                }
            } catch (Exception ex) {
                log.debug("provenance: failed to load llm_call_log id={}, continuing", llmCallId);
            }
        }

        // 4. 计算 diff_hash = SHA-256(oldContent + "\n" + newContent)
        String diffHash = HashUtils.sha256(
                (oldContent == null ? "" : oldContent) + "\n" + (newContent == null ? "" : newContent));

        // 5. 计算 record_hash
        String decidedAtStr = decidedAt.toString();
        String recordHash = HashUtils.sha256Chain(
                prevHash,
                String.valueOf(repoId),
                String.valueOf(nextSeq),
                changeId == null ? "" : String.valueOf(changeId),
                decision,
                approverId == null ? "" : String.valueOf(approverId),
                decidedAtStr
        );

        // 6. 读取明文开关（默认 false，隐私红线）
        String promptSnapshot = null;
        if (isCapturePlaintext()) {
            // 仅在明文开关为 true 时才写 prompt 快照；此处暂无 prompt 文本，供扩展
            promptSnapshot = null; // placeholder - populated by caller if needed
        }

        // 7. 构建并持久化记录
        AiContributionRecordEntity record = new AiContributionRecordEntity();
        record.setRepoId(repoId);
        record.setSeq(nextSeq);
        record.setChangeId(changeId);
        record.setLlmCallId(llmCallId);
        record.setAgentRunId(agentRunId);
        record.setProvider(provider);
        record.setModelName(modelName);
        record.setModelVersion(modelVersion);
        record.setPromptHash(promptHash);
        record.setContextHash(contextHash);
        record.setPromptSnapshot(promptSnapshot);
        record.setFilePath(filePath);
        record.setDiffHash(diffHash);
        record.setDecision(decision);
        record.setApproverId(approverId);
        record.setDecidedAt(decidedAt);
        record.setPrevHash(prevHash);
        record.setRecordHash(recordHash);

        recordMapper.insert(record);
        log.info("provenance: appended record repoId={} seq={} changeId={} decision={}",
                repoId, nextSeq, changeId, decision);
    }

    @Override
    public List<AiContributionRecordEntity> listRecords(Long repoId, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), 100);
        int offset = Math.max(0, page) * safeSize;
        return recordMapper.selectPageByRepoId(repoId, offset, safeSize);
    }

    @Override
    public long countRecords(Long repoId) {
        return recordMapper.countByRepoId(repoId);
    }

    @Override
    public AiContributionRecordEntity getRecord(Long id) {
        return recordMapper.selectById(id);
    }

    @Override
    public VerifyResult verifyChain(Long repoId) {
        List<AiContributionRecordEntity> records = recordMapper.selectAllByRepoIdOrderBySeq(repoId);
        if (records.isEmpty()) {
            return VerifyResult.ok();
        }
        String expectedPrevHash = AiContributionRecordEntity.GENESIS_HASH;
        for (AiContributionRecordEntity r : records) {
            String recomputed = HashUtils.sha256Chain(
                    expectedPrevHash,
                    String.valueOf(r.getRepoId()),
                    String.valueOf(r.getSeq()),
                    r.getChangeId() == null ? "" : String.valueOf(r.getChangeId()),
                    r.getDecision(),
                    r.getApproverId() == null ? "" : String.valueOf(r.getApproverId()),
                    r.getDecidedAt() == null ? "" : r.getDecidedAt().toString()
            );
            if (!recomputed.equals(r.getRecordHash())) {
                return VerifyResult.broken(r.getSeq());
            }
            expectedPrevHash = r.getRecordHash();
        }
        return VerifyResult.ok();
    }

    /** 读取 audit.capture-plaintext 开关（默认 false = 只存哈希，隐私红线）。 */
    private boolean isCapturePlaintext() {
        try {
            AppSettingEntity setting = appSettingMapper.selectById(KEY_CAPTURE_PLAINTEXT);
            return setting != null && "true".equalsIgnoreCase(setting.getV());
        } catch (Exception ex) {
            return false; // fail-safe: default to privacy-safe mode
        }
    }
}
