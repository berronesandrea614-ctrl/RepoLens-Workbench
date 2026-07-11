package com.repolens.kernel.web;

import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 无需 key 的兜底搜索源：抓 DuckDuckGo 的 HTML 端点解析结果，<b>开箱即用</b>（不配任何 key 就能搜）。
 * 优先级低于配了 key 的 {@link TavilySearchProvider}——用户配了 Tavily key 就走质量更高的 Tavily，
 * 没配就用本源，保证 deep-research「不设硬限制、默认能搜」。
 *
 * <p>诚实边界：DDG HTML 端点是抓页解析（非官方 API），偶发反爬/结构变动时可能少结果或失败——
 * 失败由上层 {@code WebSearchTool} 兜底转文本，agent 仍可改用 WebFetch 抓已知 URL。追求稳定/高质量
 * 就配 Tavily key。
 */
@Component
public class DuckDuckGoSearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://html.duckduckgo.com/html/?q=";

    /** 结果链接：<a ... class="result__a" ... href="URL">TITLE</a>（DOTALL 跨行）。 */
    private static final Pattern RESULT = Pattern.compile(
            "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 摘要：<a class="result__snippet" ...>SNIPPET</a>。 */
    private static final Pattern SNIPPET = Pattern.compile(
            "class=\"result__snippet\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");

    private final KernelWebClient webClient;

    public DuckDuckGoSearchProvider(KernelWebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    /** 无需 key，永远可用（兜底）。 */
    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) throws Exception {
        String url = ENDPOINT + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String html = webClient.getRaw(url, 400000, "WEB_SEARCH");

        List<String> snippets = new ArrayList<>();
        Matcher sm = SNIPPET.matcher(html);
        while (sm.find()) {
            snippets.add(unescape(stripTags(sm.group(1))));
        }

        List<SearchHit> hits = new ArrayList<>();
        Matcher m = RESULT.matcher(html);
        int i = 0;
        while (m.find() && hits.size() < Math.max(1, maxResults)) {
            String href = decodeDdgRedirect(m.group(1));
            String title = unescape(stripTags(m.group(2))).strip();
            String snippet = i < snippets.size() ? snippets.get(i) : null;
            i++;
            if (href != null && !href.isBlank() && !title.isEmpty()) {
                hits.add(new SearchHit(title, href, snippet));
            }
        }
        return hits;
    }

    /** DDG 结果 href 常是 {@code //duckduckgo.com/l/?uddg=<编码真URL>&rut=...}，解出真 URL。 */
    private static String decodeDdgRedirect(String href) {
        if (href == null) {
            return null;
        }
        String h = href.startsWith("//") ? "https:" + href : href;
        int idx = h.indexOf("uddg=");
        if (idx >= 0) {
            String enc = h.substring(idx + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) {
                enc = enc.substring(0, amp);
            }
            try {
                return URLDecoder.decode(enc, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return h;
            }
        }
        return h;
    }

    private static String stripTags(String s) {
        return s == null ? "" : TAG.matcher(s).replaceAll("").strip();
    }

    private static String unescape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&#39;", "'").replace("&apos;", "'");
    }
}
