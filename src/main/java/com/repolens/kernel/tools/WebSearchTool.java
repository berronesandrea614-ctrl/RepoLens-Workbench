package com.repolens.kernel.tools;

import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.kernel.web.KernelWebClient;
import com.repolens.kernel.web.SearchProvider;
import com.repolens.llm.model.ToolDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * {@code WebSearch} 工具：给查询词、返回标题+URL+摘要列表（对齐 Claude Code 的 WebSearch 语义——只给链接，
 * 正文靠 {@code WebFetch}）。搜索源可插拔（见 {@link SearchProvider}）：配了 key 的 provider 自动生效。
 *
 * <p>两级优雅降级，绝不因缺配置报错崩掉：
 * <ol>
 *   <li>联网默认关（0 出网红线）→ 回一句如何开启；</li>
 *   <li>联网开了但没有任何 provider 配置 key → 回一句如何配 key，并提示可改用 WebFetch 抓已知 URL。</li>
 * </ol>
 * readOnly=true。
 */
@Component
public class WebSearchTool implements KernelTool {

    private static final int DEFAULT_MAX_RESULTS = 5;

    private final KernelWebClient webClient;
    private final List<SearchProvider> providers;

    /** 首选 provider 名（配了多个 provider 时用；空=取第一个 configured 的）。 */
    @Value("${repolens.kernel.web.search.provider:}")
    private String preferred;

    public WebSearchTool(KernelWebClient webClient, List<SearchProvider> providers) {
        this.webClient = webClient;
        this.providers = providers;
    }

    @Override
    public String name() {
        return "WebSearch";
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolDefinition definition() {
        return ToolDefinition.builder()
                .name("WebSearch")
                .description("按查询词做网络搜索，返回标题+URL+摘要列表（拿到 URL 后可用 WebFetch 读正文）。"
                        + "用于查最新/外部信息。需联网（默认可用）；搜索需配置搜索源 key（未配会告知如何配）。")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of("type", "string", "description", "搜索查询词（≥2 字符）"),
                                "maxResults", Map.of("type", "integer",
                                        "description", "（可选）返回结果数，默认 5，上限 10")),
                        "required", List.of("query")))
                .build();
    }

    @Override
    public String execute(ToolContext ctx, Map<String, Object> args) {
        String query = str(args.get("query"));
        if (query == null || query.strip().length() < 2) {
            return "WebSearch 缺少有效 query（≥2 字符）。";
        }
        if (!webClient.enabled()) {
            return KernelWebClient.DISABLED_MSG;
        }
        SearchProvider provider = pickProvider();
        if (provider == null) {
            return "WebSearch 未配置搜索源。配置任一搜索 provider 的 key 即可启用，例如："
                    + "repolens.kernel.web.search.tavily-api-key=<你的 Tavily key>。"
                    + "在此之前，如果你已知具体 URL，可改用 WebFetch 直接抓取。";
        }
        int maxResults = intArg(args.get("maxResults"), DEFAULT_MAX_RESULTS);
        try {
            List<SearchProvider.SearchHit> hits = provider.search(query.strip(), maxResults);
            if (hits == null || hits.isEmpty()) {
                String hint = "duckduckgo".equals(provider.name())
                        ? "（无 key 的 DuckDuckGo 兜底源常被搜索引擎反爬限流，结果不稳定。"
                        + "要稳定搜索请配 Tavily 免费 key：repolens.kernel.web.search.tavily-api-key=<key>。"
                        + "或者你已知具体 URL 时改用 WebFetch 直接抓。）"
                        : "";
                return "WebSearch（" + provider.name() + "）无结果：" + query + hint;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("WebSearch（").append(provider.name()).append("）结果 · 查询「").append(query).append("」：\n");
            int i = 1;
            for (SearchProvider.SearchHit h : hits) {
                sb.append(i++).append(". ").append(nz(h.title())).append('\n');
                sb.append("   ").append(nz(h.url())).append('\n');
                if (h.snippet() != null && !h.snippet().isBlank()) {
                    sb.append("   ").append(brief(h.snippet())).append('\n');
                }
            }
            sb.append("\n（要读某条正文用 WebFetch(url, prompt)。）");
            return sb.toString();
        } catch (KernelWebClient.WebAccessException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "WebSearch（" + provider.name() + "）失败：" + e.getMessage();
        }
    }

    private SearchProvider pickProvider() {
        if (providers == null || providers.isEmpty()) {
            return null;
        }
        // 显式指定的 provider 优先（配了才用）
        if (preferred != null && !preferred.isBlank()) {
            for (SearchProvider p : providers) {
                if (preferred.equalsIgnoreCase(p.name()) && p.configured()) {
                    return p;
                }
            }
        }
        // 否则取「已配置」里优先级最高的：配了 key 的 Tavily(100) > 无 key 的 DuckDuckGo(10)
        SearchProvider best = null;
        for (SearchProvider p : providers) {
            if (p.configured() && (best == null || p.priority() > best.priority())) {
                best = p;
            }
        }
        return best;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String brief(String s) {
        String one = s.replace("\n", " ").strip();
        return one.length() > 240 ? one.substring(0, 240) + "…" : one;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intArg(Object o, int def) {
        if (o == null) {
            return def;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
