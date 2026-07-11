package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GraphNodeVO {
    private String id;
    private String label;
    private String className;
    private String methodName;
    private String symbolType;
    private String filePath;
    private Integer startLine;
    private Integer endLine;
    private boolean resolved;
    private String signature;
    private String summary;
    private String layer;
    private String changeType;

    /** Feature A: 理解债务分（0–100），null=无债务数据（纯人写文件）。 */
    private Integer debtScore;

    /** Feature A: 债务分档颜色（RED/#e74c3c / YELLOW/#f39c12 / GREEN/#27ae60 / null=无数据）。 */
    private String debtColor;

    /** Feature J: 本节点在时间轴上首次出现的帧序号（0-based）。null=非时间轴场景。 */
    private Integer firstSeenFrame;

    /** Feature J: 本节点在时间轴上被触碰的帧数（触碰次数）。null=非时间轴场景。 */
    private Integer touchCount;
}
