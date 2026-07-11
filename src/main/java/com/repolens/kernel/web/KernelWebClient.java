package com.repolens.kernel.web;

import com.repolens.service.EgressPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 内核侧联网客户端：所有联网工具（WebFetch/WebSearch/搜索 provider）的唯一 HTTP 出口。
 *
 * <p><b>「获取知识进来」≠「把数据送出去」</b>：联网搜索/抓取是把<b>外界公开知识取回</b>（query 是话题词、
 * fetch 是读公开网页），并非把用户私有代码<b>泄漏出去</b>。真正要守的「0 出网」红线针对的是后者
 * （LLM/embedding 调用带着用户代码出网），由 {@link EgressPolicy} 治理；联网研究属前者，<b>默认放行</b>、不硬限制。
 *
 * <p>两级把关：
 * <ol>
 *   <li><b>内核开关</b> {@code repolens.kernel.web.enabled}（<b>默认 true</b>）：仅供想要「纯离线部署」的场景
 *       显式关闭；默认联网工具可用，deep-research 等开箱即研究；</li>
 *   <li><b>EgressPolicy</b>（Feature G）：每次联网过应用层出网策略网关做<b>审计</b>并<b>尊重用户显式选的隐私档</b>
 *       ——默认 OPEN 放行并写 {@code egress_log}；仅当用户主动切 LOCAL_ONLY/ALLOWLIST（要极致隐私）时才拦。</li>
 * </ol>
 *
 * <p>{@link EgressPolicy} 用 {@code @Autowired(required=false)} 软注入：内核最小 E2E 上下文不含它时，
 * 只依赖内核开关门控（不做主机级审计），生产全量上下文里它恒存在，出网必过审计。
 *
 * <p>与旧 god-class 区的 {@code service.impl.support.WebFetcher} 物理隔离——本类是内核自有实现，
 * 不引 {@code service.impl.support}，维持重写内核的独立性。
 */
@Component
public class KernelWebClient {

    private static final Logger log = LoggerFactory.getLogger(KernelWebClient.class);

    /** 联网工具默认开（获取外界知识≠泄漏用户数据，不硬限制）。仅纯离线部署才显式设 false。 */
    @Value("${repolens.kernel.web.enabled:true}")
    private boolean enabled;

    /** 单次请求超时秒数。 */
    @Value("${repolens.kernel.web.timeout-seconds:20}")
    private int timeoutSeconds;

    @Autowired(required = false)
    private EgressPolicy egressPolicy;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static final String DISABLED_MSG =
            "联网工具已被显式关闭（repolens.kernel.web.enabled=false，通常是纯离线部署）。"
                    + "如需联网研究，请把它设回 true。在此之前请改用不联网的方式完成。";

    public boolean enabled() {
        return enabled;
    }

    /** 出网前置门：内核开关 + EgressPolicy 审计/放行。被拒抛异常（工具侧 catch 转文本）。 */
    public void ensureAllowed(String host, int port, String purpose) {
        if (!enabled) {
            throw new WebAccessException(DISABLED_MSG);
        }
        if (egressPolicy != null) {
            // 策略拒绝会抛 BizException（403），一并冒泡给工具侧转成自然语言错误。
            egressPolicy.checkAndLog(host, port, purpose, null);
        }
    }

    /**
     * GET 抓取并返回处理后的文本（HTML 粗剥标签、按 maxChars 截断）。
     *
     * @return 抓取结果；网络层失败以 status=-1 承载错误描述，不抛（业务可读错自愈）；
     *         出网被门控则抛 {@link WebAccessException}/BizException。
     */
    public FetchResult fetch(String url, int maxChars) {
        URI uri = URI.create(url);
        ensureAllowed(uri.getHost(), effectivePort(uri), "WEB_FETCH");
        int cap = clamp(maxChars, 1, 40000);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                    .header("User-Agent", "RepoLens-Kernel/1.0 (+web-tool)")
                    .header("Accept", "text/html,application/json,text/plain,*/*")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            String contentType = resp.headers().firstValue("content-type").orElse("");
            String processed = WebText.process(resp.body(), contentType);
            boolean truncated = processed.length() > cap;
            if (truncated) {
                processed = processed.substring(0, cap);
            }
            return new FetchResult(url, resp.statusCode(), contentType, truncated, processed);
        } catch (Exception e) {
            log.debug("[web] fetch 失败 {}: {}", url, e.getMessage());
            return new FetchResult(url, -1, null, false, "抓取失败：" + e.getMessage());
        }
    }

    /**
     * POST JSON（搜索 provider 用）。出网门控同 {@link #fetch}。返回响应体字符串。
     *
     * @throws Exception 网络/协议层异常（provider 侧负责转文本）；出网被门控则抛 WebAccessException/BizException
     */
    public String postJson(String url, String jsonBody, String purpose) throws Exception {
        URI uri = URI.create(url);
        ensureAllowed(uri.getHost(), effectivePort(uri), purpose);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("Content-Type", "application/json")
                .header("User-Agent", "RepoLens-Kernel/1.0 (+web-tool)")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + "：" + brief(resp.body()));
        }
        return resp.body();
    }

    /**
     * GET 返回<b>原始</b>响应体（不剥 HTML）。搜索 provider 解析结果结构时用——{@link #fetch} 会把标签剥掉，
     * 拿不到 &lt;a&gt; 链接。出网门控同 {@link #fetch}。
     *
     * @throws Exception 网络/协议层异常（provider 侧转文本）；出网被门控则抛 WebAccessException/BizException
     */
    public String getRaw(String url, int maxChars, String purpose) throws Exception {
        URI uri = URI.create(url);
        ensureAllowed(uri.getHost(), effectivePort(uri), purpose);
        int cap = clamp(maxChars, 1, 400000);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body() == null ? "" : resp.body();
        return body.length() > cap ? body.substring(0, cap) : body;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /**
     * 抓取结果。
     *
     * @param url         原始 URL
     * @param status      HTTP 状态码；-1=网络/协议层失败
     * @param contentType 响应 Content-Type
     * @param truncated   是否被截断
     * @param content     处理后的文本
     */
    public record FetchResult(String url, int status, String contentType, boolean truncated, String content) {
    }

    /** 出网被门控（内核开关关 / 策略拒绝）时抛出，工具侧 catch 后以自然语言回给 agent。 */
    public static class WebAccessException extends RuntimeException {
        public WebAccessException(String message) {
            super(message);
        }
    }
}
