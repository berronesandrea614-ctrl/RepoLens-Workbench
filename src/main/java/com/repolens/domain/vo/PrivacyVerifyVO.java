package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 隐私链路校验结果 VO（Feature G-P2 可验证/可导出证明）。
 *
 * <p>每个检查项用 {@link CheckResult} 表达 PASS/FAIL + 原因，
 * 最终 {@code verdict=true} 当且仅当全部检查通过且当前模式为 LOCAL_ONLY。
 *
 * <p><b>边界声明</b>：本校验仅覆盖应用层网关；完整零出网证明需配合 JFR/OS 层（lsof/tcpdump）交叉验证。
 */
@Data
@Builder
public class PrivacyVerifyVO {

    /** 单项校验结果。 */
    @Data
    @Builder
    public static class CheckResult {
        /** true = PASS，false = FAIL。 */
        private boolean passed;
        /** 人可读的结果说明。 */
        private String reason;
    }

    /** 记录校验时的当前隐私模式。 */
    private String mode;

    /** 检查 LLM provider 是否为本地（ollama / mock，非云端）。 */
    private CheckResult llmProviderIsLocal;

    /** 检查 baseUrl 主机是否解析到回环地址（127.x / ::1 / localhost）。 */
    private CheckResult baseUrlIsLoopback;

    /** 检查本地 Ollama 端点是否可达（实际探针请求，经 EgressPolicy 回环放行）。 */
    private CheckResult ollamaReachable;

    /** 检查最近 24h 出网日志：LOCAL_ONLY 模式下是否有非回环"已放行"记录（0 = 安全）。 */
    private CheckResult recentEgressAllExternalBlocked;

    /**
     * 总体裁决：true = 全部通过且模式为 LOCAL_ONLY。
     * false = 任一检查失败或模式不是 LOCAL_ONLY。
     */
    private boolean verdict;

    /**
     * 诚实说明：本证明为应用层网关快照，JFR/OS 层交叉验证方法见 /api/privacy/report
     * 与 scripts/jfr-privacy-check.sh。
     */
    private String note;

    /** 本次检查的时间戳（ISO-8601）。 */
    private String checkedAt;

    /** 任何非预期异常的摘要（全部 PASS 时为空列表）。 */
    @Builder.Default
    private List<String> warnings = List.of();
}
