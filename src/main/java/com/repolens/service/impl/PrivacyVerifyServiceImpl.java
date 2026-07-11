package com.repolens.service.impl;

import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.domain.vo.PrivacyVerifyVO;
import com.repolens.domain.vo.PrivacyVerifyVO.CheckResult;
import com.repolens.llm.config.LlmRuntimeConfig;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.service.EgressPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 隐私链路校验服务（Feature G-P2）。
 *
 * <p>执行四项可验证检查并聚合总体裁决：
 * <ol>
 *   <li>llmProviderIsLocal — LLM provider 是否为本地（非云端）。</li>
 *   <li>baseUrlIsLoopback — baseUrl 主机是否为回环地址。</li>
 *   <li>ollamaReachable — 本地 Ollama 端点是否实际可达（探针经 EgressPolicy 放行）。</li>
 *   <li>recentEgressAllExternalBlocked — 最近 24h 无非回环放行记录。</li>
 * </ol>
 *
 * <p><b>失败安全</b>：任何单项探针失败（IO 异常、超时）均返回 FAIL + 原因，不崩溃。
 * <b>边界声明</b>：仅覆盖应用层网关；完整证明需 JFR/OS 层交叉验证。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivacyVerifyServiceImpl {

    private static final String NOTE =
            "本校验覆盖应用层出网网关（EgressPolicy）快照。完整的零出网证明需配合 JVM 层（JFR jdk.SocketWrite/" +
            "jdk.SocketRead 事件）及 OS 层（lsof -i / tcpdump / Little Snitch）交叉验证。" +
            "详见 scripts/jfr-privacy-check.sh 及 /api/privacy/report?format=txt 中的 JFR 说明。";

    /** 24 小时回溯窗口（用于 recentEgressAllExternalBlocked 检查）。 */
    static final int RECENT_HOURS = 24;

    /** Ollama 探针超时（秒）。 */
    static final int PROBE_TIMEOUT_SECONDS = 4;

    private final LlmRuntimeConfig llmRuntimeConfig;
    private final EgressPolicy egressPolicy;
    private final EgressLogMapper egressLogMapper;

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * 执行全部四项检查并返回校验结果 VO。
     * 失败安全：单项异常记录 warning，其余检查继续执行。
     */
    public PrivacyVerifyVO verify() {
        String mode = egressPolicy.getMode();
        List<String> warnings = new ArrayList<>();

        CheckResult c1 = checkLlmProviderIsLocal(llmRuntimeConfig.getProvider(), warnings);
        CheckResult c2 = checkBaseUrlIsLoopback(llmRuntimeConfig.getBaseUrl(), warnings);
        CheckResult c3 = checkOllamaReachable(llmRuntimeConfig.getBaseUrl(), warnings);
        CheckResult c4 = checkRecentEgressAllExternalBlocked(mode, warnings);

        boolean verdict = aggregateVerdict(
                c1.isPassed(), c2.isPassed(), c3.isPassed(), c4.isPassed(), mode);

        return PrivacyVerifyVO.builder()
                .mode(mode)
                .llmProviderIsLocal(c1)
                .baseUrlIsLoopback(c2)
                .ollamaReachable(c3)
                .recentEgressAllExternalBlocked(c4)
                .verdict(verdict)
                .note(NOTE)
                .checkedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .warnings(warnings)
                .build();
    }

    // ─── Pure verdict aggregation (testable without Spring) ──────────────────

    /**
     * 纯函数：聚合四项检查和模式，返回总体裁决。
     * 总体 PASS 要求：四项全 PASS，且模式为 LOCAL_ONLY。
     */
    public static boolean aggregateVerdict(
            boolean llmLocal, boolean baseUrlLoop, boolean ollamaOk,
            boolean egressClean, String mode) {
        return llmLocal && baseUrlLoop && ollamaOk && egressClean
                && EgressLogEntity.MODE_LOCAL_ONLY.equals(mode);
    }

    /**
     * 纯函数：检查 provider 是否为本地（非云端）。
     * PASS 条件：provider 为 null/空（默认 mock）、"ollama"、"mock" 或任意非云端标识。
     */
    public static CheckResult checkLlmProviderIsLocal(String provider, List<String> warnings) {
        if (!StringUtils.hasText(provider) || "mock".equalsIgnoreCase(provider.trim())) {
            return CheckResult.builder().passed(true)
                    .reason("provider=" + (StringUtils.hasText(provider) ? provider.trim() : "mock")
                            + "（本地/模拟，不出网）").build();
        }
        String norm = provider.trim().toLowerCase();
        if (LlmRuntimeConfig.isCloudProvider(norm)) {
            return CheckResult.builder().passed(false)
                    .reason("provider=" + provider.trim() + " 是云端服务，将出网，请切换为 ollama 或 mock").build();
        }
        return CheckResult.builder().passed(true)
                .reason("provider=" + provider.trim() + "（非云端标识，视为本地）").build();
    }

    /**
     * 纯函数：检查 baseUrl 主机是否为回环地址。
     * baseUrl 为空 → PASS（mock/无 URL 配置，不出网）。
     */
    public static CheckResult checkBaseUrlIsLoopback(String baseUrl, List<String> warnings) {
        if (!StringUtils.hasText(baseUrl)) {
            return CheckResult.builder().passed(true)
                    .reason("baseUrl 未配置（mock 模式，不出网）").build();
        }
        boolean loopback = LlmRuntimeConfig.isLoopbackBaseUrl(baseUrl.trim());
        if (loopback) {
            return CheckResult.builder().passed(true)
                    .reason("baseUrl=" + baseUrl.trim() + " 主机解析到回环地址").build();
        }
        return CheckResult.builder().passed(false)
                .reason("baseUrl=" + baseUrl.trim() + " 主机解析到非回环地址，将出网").build();
    }

    // ─── IO-bound checks (not purely static; failure-safe) ───────────────────

    /**
     * 探测 Ollama 端点是否可达。
     * 探针本身必须经过 EgressPolicy（回环时允许，非回环时抛 BizException → FAIL）。
     * 使用 JDK 内置 HttpClient（Java 11+），无需额外依赖。
     */
    CheckResult checkOllamaReachable(String baseUrl, List<String> warnings) {
        if (!StringUtils.hasText(baseUrl)) {
            return CheckResult.builder().passed(false)
                    .reason("baseUrl 未配置，无法探测 Ollama").build();
        }
        String normalized = baseUrl.trim();
        // 提取 host 和 port 以便 EgressPolicy 检查
        String host;
        int port;
        try {
            URI uri = URI.create(normalized);
            host = uri.getHost();
            port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        } catch (Exception e) {
            return CheckResult.builder().passed(false)
                    .reason("baseUrl 格式无效，无法解析 host：" + e.getMessage()).build();
        }

        // EgressPolicy 门（回环 → 放行记录；非回环 LOCAL_ONLY → 抛异常）
        try {
            egressPolicy.checkAndLog(host, port, "VERIFY_PROBE", null);
        } catch (BizException e) {
            return CheckResult.builder().passed(false)
                    .reason("EgressPolicy 拦截探针：" + e.getMessage()).build();
        } catch (Exception e) {
            warnings.add("EgressPolicy.checkAndLog 异常（失败安全）: " + e.getMessage());
        }

        // 实际 HTTP 探针（GET {baseUrl}/api/tags → Ollama tags endpoint）
        String probeUrl = normalized.replaceAll("/+$", "") + "/api/tags";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(probeUrl))
                    .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            int status = resp.statusCode();
            if (status < 500) {
                return CheckResult.builder().passed(true)
                        .reason("Ollama 响应 HTTP " + status + "（端点：" + probeUrl + "）").build();
            }
            return CheckResult.builder().passed(false)
                    .reason("Ollama 返回 HTTP " + status + "（端点：" + probeUrl + "）").build();
        } catch (java.net.ConnectException e) {
            return CheckResult.builder().passed(false)
                    .reason("连接被拒绝（Connection refused）：" + probeUrl + " — Ollama 未运行？").build();
        } catch (java.net.http.HttpTimeoutException e) {
            return CheckResult.builder().passed(false)
                    .reason("探针超时（>" + PROBE_TIMEOUT_SECONDS + "s）：" + probeUrl).build();
        } catch (Exception e) {
            return CheckResult.builder().passed(false)
                    .reason("探针失败：" + e.getClass().getSimpleName() + " — " + e.getMessage()).build();
        }
    }

    /**
     * 检查最近 24h 是否有非回环"放行"记录。
     * LOCAL_ONLY 模式下期望为 0；其他模式本项直接 FAIL（模式不是 LOCAL_ONLY，证明不成立）。
     */
    public CheckResult checkRecentEgressAllExternalBlocked(String mode, List<String> warnings) {
        if (!EgressLogEntity.MODE_LOCAL_ONLY.equals(mode)) {
            return CheckResult.builder().passed(false)
                    .reason("当前模式为 " + mode + "，需切换到 LOCAL_ONLY 方可验证零出网").build();
        }
        LocalDateTime since = LocalDateTime.now().minusHours(RECENT_HOURS);
        try {
            long leaked = egressLogMapper.countRecentNonLoopbackAllowed(since);
            if (leaked == 0) {
                return CheckResult.builder().passed(true)
                        .reason("最近 " + RECENT_HOURS + "h 无非回环放行记录（外网出站字节 = 0，应用层）").build();
            }
            return CheckResult.builder().passed(false)
                    .reason("最近 " + RECENT_HOURS + "h 发现 " + leaked + " 条非回环放行记录（外网有出站）").build();
        } catch (Exception e) {
            warnings.add("egress_log 查询失败（失败安全）: " + e.getMessage());
            return CheckResult.builder().passed(false)
                    .reason("egress_log 查询失败，无法确认：" + e.getMessage()).build();
        }
    }
}
