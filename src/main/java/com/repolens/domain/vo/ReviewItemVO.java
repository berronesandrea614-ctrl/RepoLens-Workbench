package com.repolens.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 待审查的风险条目 VO（H Mission Control P1）。
 * 对应一条未确认的 change_risk_flag，附上文件路径供前端展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewItemVO {

    /** change_risk_flag.changeId（即 file_change_log.id） */
    private Long changeId;

    /** 风险类别（来自 change_risk_flag.category）：DESTRUCTIVE/TEST_WEAKENED/SECURITY/SCOPE */
    private String kind;

    /** 可逆性：IRREVERSIBLE / REVERSIBLE */
    private String reversibility;

    /** 严重程度：BLOCK / WARN */
    private String severity;

    /** 是否打断执行（reversibility=IRREVERSIBLE && severity=BLOCK） */
    private boolean interrupt;

    /** 关联文件路径（经 changeId → file_change_log.filePath 查询） */
    private String filePath;

    /** 风险证据（匹配行或上下文） */
    private String evidence;
}
