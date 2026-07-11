package com.repolens.service.impl.support;

/**
 * HTTP 内容抓取接口（为 webFetch 工具提供可注入的 HTTP 实现）。
 *
 * <p>接口分离目的：
 * <ul>
 *   <li>生产：{@link DefaultWebFetcher} 使用单例 {@link java.net.http.HttpClient}；</li>
 *   <li>测试：测试 stub 无需真实网络，可离线验证截断/HTML 剥离/错误降级逻辑。</li>
 * </ul>
 *
 * <p>SSRF 校验由调用方（{@code ToolInvokeServiceImpl}）在调用此接口前完成，
 * 此接口本身不做 SSRF 防护，专注纯 HTTP 内容抓取与格式处理。
 */
public interface WebFetcher {

    /**
     * 抓取指定 URL 的内容并返回处理后的结果。
     *
     * <ul>
     *   <li>HTML 内容自动粗剥标签（去 script/style 块 + 标签→空格 + 压缩空白）；</li>
     *   <li>非 HTML（json/text 等）原样返回；</li>
     *   <li>内容超过 {@code maxChars} 时截断，{@code truncated=true}；</li>
     *   <li>网络错误/超时等：返回 {@code status=-1}，{@code content} 为错误描述，不抛异常。</li>
     * </ul>
     *
     * @param url      目标 URL（调用方已校验 http/https + SSRF）
     * @param maxChars 内容字符上限（1–40000），超出则截断
     * @return 抓取结果
     */
    FetchResult fetch(String url, int maxChars);

    /**
     * 抓取结果。
     *
     * @param url         原始请求 URL
     * @param status      HTTP 状态码；-1 表示网络/协议层失败
     * @param contentType 响应 Content-Type（失败时为 null）
     * @param truncated   内容是否被截断
     * @param content     处理后的文本内容
     */
    record FetchResult(String url, int status, String contentType, boolean truncated, String content) {
    }
}
