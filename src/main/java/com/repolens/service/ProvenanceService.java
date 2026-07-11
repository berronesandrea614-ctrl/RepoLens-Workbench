package com.repolens.service;

import com.repolens.domain.entity.AiContributionRecordEntity;

import java.util.List;

/**
 * AI 贡献溯源账本服务（Feature F）。
 *
 * 核心职责：
 * 1. 接收变更决策事件，串接哈希链并持久化一条 ai_contribution_record。
 * 2. 所有写入均 FAILURE-SAFE（失败不阻断主链路，参考 persistToolCallLogSafe 模式）。
 * 3. 提供哈希链完整性验证（重算整链，检测篡改）。
 * 4. 提供分页查询与单条查询（供 ProvenanceController 使用）。
 */
public interface ProvenanceService {

    /**
     * 追加一条账本记录（失败安全）。
     * 在 FileChangeServiceImpl.applyOne / rejectOne / revert 末尾调用。
     *
     * @param repoId      仓库 id
     * @param changeId    file_change_log.id
     * @param filePath    变更文件路径
     * @param oldContent  变更前内容（用于 diff_hash 计算）
     * @param newContent  变更后内容（用于 diff_hash 计算）
     * @param llmCallId   关联 llm_call_log.id（无则 null）
     * @param agentRunId  关联 agent_run.id（无则 null）
     * @param decision    APPROVED / REJECTED / REVERTED
     * @param approverId  操作人 user_id
     */
    void appendRecordSafe(Long repoId, Long changeId, String filePath,
                          String oldContent, String newContent,
                          Long llmCallId, Long agentRunId,
                          String decision, Long approverId);

    /**
     * 分页查询 repo 账本（最新在前）。
     *
     * @param repoId 仓库 id
     * @param page   从 0 开始的页码
     * @param size   每页条数（≤ 100）
     * @return 记录列表
     */
    List<AiContributionRecordEntity> listRecords(Long repoId, int page, int size);

    /**
     * 查询 repo 账本总数（用于分页）。
     */
    long countRecords(Long repoId);

    /**
     * 按 id 查询单条记录。
     */
    AiContributionRecordEntity getRecord(Long id);

    /**
     * 校验 repo 账本哈希链完整性。
     * 顺序遍历所有记录，重算每条 record_hash；任何不一致返回 {verified=false, brokenAtSeq}。
     *
     * @return 校验结果
     */
    VerifyResult verifyChain(Long repoId);

    /** 哈希链校验结果。 */
    record VerifyResult(boolean verified, Long brokenAtSeq) {
        public static VerifyResult ok() {
            return new VerifyResult(true, null);
        }
        public static VerifyResult broken(long seq) {
            return new VerifyResult(false, seq);
        }
    }
}
