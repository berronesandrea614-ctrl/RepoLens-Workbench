package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("llm_call_log")
public class LlmCallLogEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long repoId;

    private Long sessionId;

    private String modelName;

    private String promptHash;

    private String responseHash;

    private Integer tokenInput;

    private Integer tokenOutput;

    private Long costMs;

    private Boolean success;

    private String errorCode;

    // ---- Feature F: P1 trace columns ----

    /** LLM provider（deepseek/ollama/openai 等），从 model 名推断。 */
    private String provider;

    /** 完整模型版本字符串，来自 LLM 响应或请求 modelName。 */
    private String modelVersion;

    /** 检索上下文哈希（tool-role 消息内容的 SHA-256）；用于溯源账本。 */
    private String contextHash;
}
