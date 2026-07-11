package com.repolens.service.impl.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 基于 JDK {@link HttpClient} 的真实 HTTP 内容抓取器。
 *
 * <p>设计要点：
 * <ul>
 *   <li>单例 {@link #HTTP_CLIENT}：复用连接池，避免每次 new 带来的性能开销；</li>
 *   <li>读体上限 512KB（{@link #MAX_BODY_BYTES}），防大文件撑爆内存；</li>
 *   <li>HTTP 请求超时 15 秒；</li>
 *   <li>HTML 粗剥：去 script/style 块 → 标签替换空格 → 压缩空白，保留可读文本；</li>
 *   <li>失败安全：任何网络/IO 异常不向上抛，返回 {@code status=-1} 描述。</li>
 * </ul>
 *
 * <p>注意：SSRF 校验由调用方在调用此类前完成；此类不做 SSRF 防护。
 */
@Slf4j
@Component
public class DefaultWebFetcher implements WebFetcher {

    /** 共享 HTTP 客户端（连接级超时 15s，重用连接池）。 */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 响应体读取上限：512KB（读完再剥离截断；防大文件撑爆）。 */
    static final int MAX_BODY_BYTES = 512 * 1024;

    /** 请求级超时（含等待响应头）。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // HTML 剥离正则（粗粒度，足够让 LLM 读懂文档内容）
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "<script[^>]*>[\\s\\S]*?</script>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile(
            "<style[^>]*>[\\s\\S]*?</style>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \\t\\r\\n]{2,}");

    @Override
    public FetchResult fetch(String url, int maxChars) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "RepoLens-Agent/1.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            String contentType = response.headers()
                    .firstValue("content-type").orElse(null);

            // 读取响应体，最多 MAX_BODY_BYTES 字节（超出直接截断）
            byte[] bodyBytes;
            try (InputStream is = response.body()) {
                bodyBytes = is.readNBytes(MAX_BODY_BYTES);
            }

            // 确定编码（简单从 content-type 提取，默认 UTF-8）
            Charset charset = extractCharset(contentType);
            String raw = new String(bodyBytes, charset);

            // HTML 内容剥标签；其他类型原样返回
            boolean isHtml = contentType != null
                    && contentType.toLowerCase().contains("text/html");
            String text = isHtml ? stripHtml(raw) : raw;

            boolean truncated = text.length() > maxChars;
            String content = truncated ? text.substring(0, maxChars) : text;

            return new FetchResult(url, status, contentType, truncated, content);

        } catch (Exception ex) {
            log.debug("webFetch failed url={}, reason={}", url, ex.getMessage());
            return new FetchResult(url, -1, null, false, "抓取失败：" + ex.getMessage());
        }
    }

    /** 从 Content-Type 头中提取 charset，默认返回 UTF-8。 */
    private static Charset extractCharset(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        // charset=xxx
        int idx = contentType.toLowerCase().indexOf("charset=");
        if (idx >= 0) {
            String cs = contentType.substring(idx + 8).trim().split("[;\\s]")[0];
            try {
                return Charset.forName(cs);
            } catch (Exception ignore) {
                // unsupported charset → fallback
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 粗剥 HTML 标签，保留纯文本。
     * 步骤：1) 去 script/style 块；2) 标签替换空格；3) 压缩连续空白。
     */
    static String stripHtml(String html) {
        String s = SCRIPT_BLOCK.matcher(html).replaceAll(" ");
        s = STYLE_BLOCK.matcher(s).replaceAll(" ");
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = WHITESPACE_RUN.matcher(s).replaceAll(" ");
        return s.strip();
    }
}
