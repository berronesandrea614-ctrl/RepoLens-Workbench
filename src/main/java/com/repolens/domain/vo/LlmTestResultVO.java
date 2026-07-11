package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

/**
 * LLM 连接测试结果。ok=true 表示配置可用；message 为可读结果（成功提示或已脱敏的错误摘要）。
 */
@Data
@Builder
public class LlmTestResultVO {

    private boolean ok;

    private String message;
}
