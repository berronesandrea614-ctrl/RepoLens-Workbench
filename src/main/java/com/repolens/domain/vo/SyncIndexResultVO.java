package com.repolens.domain.vo;

import com.repolens.domain.enums.TaskStatus;
import lombok.Builder;
import lombok.Data;

/**
 * 同步一键索引（import → parse → chunks → vectors）的汇总结果。
 * status 语义：
 * - SUCCESS：四个阶段全部完成，repo 已进入 INDEXED；
 * - FAILED：某个阶段失败，failedStage/errorMsg 指明失败点，repo 已置 FAILED（不会残留 INDEXING）；
 * - RUNNING：已有索引流程持有 repo 锁，本次未执行。
 */
@Data
@Builder
public class SyncIndexResultVO {

    private Long repoId;

    private TaskStatus status;

    /** 失败阶段：CLONE_REPO / PARSE_CODE / BUILD_CHUNK / VECTORIZE_CHUNK；成功时为 null。 */
    private String failedStage;

    private String errorMsg;

    private String traceId;

    private Long costMs;

    private ImportRepoResultVO importResult;

    private ParseRepoResultVO parseResult;

    private BuildChunkResultVO chunkResult;

    private VectorizeResultVO vectorizeResult;
}
