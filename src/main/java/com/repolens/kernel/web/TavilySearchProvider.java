package com.repolens.kernel.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Tavily 搜索 provider（AI-native 搜索 API，有免费额度）。配了 API key 即启用；出网走 {@link KernelWebClient}
 * 的两层门控（内核开关 + EgressPolicy）。key 未配则 {@link #configured()} 返回 false，不参与选择。
 *
 * <p>配置：{@code repolens.kernel.web.search.tavily-api-key=<key>}（可放 .env / 环境变量 / settings）。
 * 端点 {@code https://api.tavily.com/search}，请求体 {@code {api_key, query, max_results, search_depth}}，
 * 响应 {@code results[].{title,url,content}}。
 */
@Component
public class TavilySearchProvider implements SearchProvider {

    private static final String ENDPOINT = "https://api.tavily.com/search";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KernelWebClient webClient;

    @Value("${repolens.kernel.web.search.tavily-api-key:}")
    private String apiKey;

    public TavilySearchProvider(KernelWebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public String name() {
        return "tavily";
    }

    @Override
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 配了 key 的高质量源，优先于无 key 的 DuckDuckGo 兜底。 */
    @Override
    public int priority() {
        return 100;
    }

    @Override
    public List<SearchHit> search(String query, int maxResults) throws Exception {
        int n = Math.max(1, Math.min(maxResults, 10));
        var reqBody = MAPPER.createObjectNode();
        reqBody.put("api_key", apiKey);
        reqBody.put("query", query);
        reqBody.put("max_results", n);
        reqBody.put("search_depth", "basic");
        String json = MAPPER.writeValueAsString(reqBody);

        String resp = webClient.postJson(ENDPOINT, json, "WEB_SEARCH");
        JsonNode root = MAPPER.readTree(resp);
        JsonNode results = root.get("results");
        List<SearchHit> hits = new ArrayList<>();
        if (results != null && results.isArray()) {
            for (JsonNode r : results) {
                String title = text(r, "title");
                String url = text(r, "url");
                String content = text(r, "content");
                if (url != null && !url.isBlank()) {
                    hits.add(new SearchHit(title, url, content));
                }
            }
        }
        return hits;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
