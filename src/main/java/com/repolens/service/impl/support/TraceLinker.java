package com.repolens.service.impl.support;

import com.repolens.domain.enums.SymbolType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Feature C: 纯静态工具 — trace link 去重合并、层推断、孤儿/悬空检测、覆盖率计算。
 * 无 Spring 注解，无 DB 访问，完全可单测。
 */
public final class TraceLinker {

    private static final int MAX_MERGED_NODES = 150;

    private TraceLinker() {}

    /**
     * 一条链接候选：(requirement, symbol) 对 + 来源类型 + 置信度。
     */
    public record LinkCandidate(
            Long requirementId,
            Long symbolId,
            String filePath,
            String linkType,
            double confidence) {}

    /**
     * 合并多路来源的候选链接：按 (requirementId, symbolId) 去重，保留置信度最高的，
     * 若置信度相同优先 DECLARED > CALLGRAPH > RAG > MANUAL。
     * 结果按置信度降序截断到 MAX_MERGED_NODES。
     */
    public static List<LinkCandidate> mergeAndDedup(List<LinkCandidate> candidates) {
        // key = requirementId + ":" + symbolId (null symbolId treated as "null")
        Map<String, LinkCandidate> best = new LinkedHashMap<>();
        for (LinkCandidate c : candidates) {
            String key = c.requirementId() + ":" + c.symbolId();
            LinkCandidate existing = best.get(key);
            if (existing == null || isBetter(c, existing)) {
                best.put(key, c);
            }
        }
        List<LinkCandidate> sorted = new ArrayList<>(best.values());
        sorted.sort(Comparator.comparingDouble(LinkCandidate::confidence).reversed());
        if (sorted.size() > MAX_MERGED_NODES) {
            return sorted.subList(0, MAX_MERGED_NODES);
        }
        return sorted;
    }

    /** Returns true if candidate {@code a} is better than {@code b}. */
    private static boolean isBetter(LinkCandidate a, LinkCandidate b) {
        if (a.confidence() > b.confidence()) return true;
        if (a.confidence() < b.confidence()) return false;
        return linkTypePriority(a.linkType()) < linkTypePriority(b.linkType());
    }

    private static int linkTypePriority(String linkType) {
        return switch (linkType == null ? "" : linkType) {
            case "DECLARED" -> 0;
            case "CALLGRAPH" -> 1;
            case "RAG" -> 2;
            case "MANUAL" -> 3;
            default -> 4;
        };
    }

    /**
     * 推断 SymbolType 对应的 UI 层标签（对齐 graphLayout.ts LAYER_COLORS）。
     * CONFIG 等不映射到核心层，返回 null。
     */
    public static String inferLayer(SymbolType symbolType) {
        if (symbolType == null) return null;
        return switch (symbolType) {
            case CONTROLLER, API -> "Controller";
            case SERVICE, METHOD -> "Service";
            case MAPPER -> "Mapper";
            case ENTITY, CLASS -> "Entity";
            default -> null;
        };
    }

    /**
     * 是否为核心层符号（Controller/Service/Mapper/Entity 及其别名）。
     * 孤儿检测仅针对核心层符号，CONFIG 不算孤儿。
     */
    public static boolean isCoreLayer(SymbolType symbolType) {
        if (symbolType == null) return false;
        return switch (symbolType) {
            case CONTROLLER, API, SERVICE, METHOD, MAPPER, ENTITY, CLASS -> true;
            default -> false;
        };
    }

    /**
     * 覆盖率 = 有 LINKED link 的需求数 / 全部需求数。
     * 无需求时返回 1.0（定义为"没有需求也没有孤儿"）。
     */
    public static double computeCoverage(Set<Long> allReqIds, Set<Long> linkedReqIds) {
        if (allReqIds.isEmpty()) return 1.0;
        long linked = allReqIds.stream().filter(linkedReqIds::contains).count();
        return (double) linked / allReqIds.size();
    }
}
