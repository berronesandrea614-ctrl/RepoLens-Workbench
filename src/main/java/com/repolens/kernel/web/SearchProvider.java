package com.repolens.kernel.web;

import java.util.List;

/**
 * 网络搜索 provider 抽象——可插拔搜索源。{@code WebSearch} 工具在此接口之上工作，
 * 具体搜索 API（Tavily / Brave / SerpAPI…）各实现一个 bean，配了 key 的自动生效。
 *
 * <p>未配置任何 provider 时，{@code WebSearch} 优雅回一句「未配置搜索源」而非报错——
 * 让「先把系统建完、key 以后配」成为可行路径（不阻塞集成）。
 */
public interface SearchProvider {

    /** provider 名（如 tavily）。 */
    String name();

    /** 是否已配置可用（通常=有 API key）。未配置的 provider 不参与选择。 */
    boolean configured();

    /** 选择优先级（越大越优先）。配了 key 的高质量源（如 Tavily=100）应高于无 key 的兜底源（如 DuckDuckGo=10）。 */
    default int priority() {
        return 50;
    }

    /**
     * 执行搜索。
     *
     * @param query      查询词
     * @param maxResults 期望结果数上限
     * @return 命中列表（标题 + URL + 摘要）
     * @throws Exception 网络/协议/鉴权失败（工具侧转自然语言错误）
     */
    List<SearchHit> search(String query, int maxResults) throws Exception;

    /**
     * 一条搜索命中。
     *
     * @param title   标题
     * @param url     链接
     * @param snippet 摘要（provider 给的正文片段，可空）
     */
    record SearchHit(String title, String url, String snippet) {
    }
}
