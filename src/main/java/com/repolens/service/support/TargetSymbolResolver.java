package com.repolens.service.support;

import com.repolens.domain.entity.CodeSymbolEntity;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把 code_dependency.target_symbol_name 字符串解析回符号 id。
 * target 形态：全限定 "com.x.UserService#getUser"、简单 "UserService#getUser"、
 * 纯文本 "getUser" 或 "scope.getUser"。静态近似，无编译级类型绑定。
 * 注：每次 buildGraph 内部 new 一个局部实例（有可变索引状态，非单例）。
 */
public class TargetSymbolResolver {

    private static final int MAX_MATCHES = 5;

    private final Map<String, List<Long>> byMethodName = new HashMap<>();
    private final Map<String, List<Long>> byClassHashMethod = new HashMap<>();
    // 用完整 className（AST 解析出的全限定名）建的索引，优先于简单名匹配，减少同名方法的过度匹配。
    private final Map<String, List<Long>> byFullClassHashMethod = new HashMap<>();

    /** 每次 buildGraph 前用某 repo 的全部符号重建索引。 */
    public void index(List<CodeSymbolEntity> symbols) {
        byMethodName.clear();
        byClassHashMethod.clear();
        byFullClassHashMethod.clear();
        for (CodeSymbolEntity s : symbols) {
            if (s == null || s.getId() == null || !StringUtils.hasText(s.getMethodName())) {
                continue;
            }
            String method = s.getMethodName().toLowerCase(Locale.ROOT);
            byMethodName.computeIfAbsent(method, k -> new ArrayList<>()).add(s.getId());
            String fullClass = s.getClassName();
            if (StringUtils.hasText(fullClass)) {
                String fullKey = (fullClass.trim() + "#" + s.getMethodName()).toLowerCase(Locale.ROOT);
                byFullClassHashMethod.computeIfAbsent(fullKey, k -> new ArrayList<>()).add(s.getId());
            }
            String simpleClass = simpleName(fullClass);
            if (StringUtils.hasText(simpleClass)) {
                String key = (simpleClass + "#" + s.getMethodName()).toLowerCase(Locale.ROOT);
                byClassHashMethod.computeIfAbsent(key, k -> new ArrayList<>()).add(s.getId());
            }
        }
    }

    /**
     * 常规解析：优先级 全限定类#方法 &gt; 简单类#方法 &gt; 纯方法名。
     * 当 target 携带全限定类名（AST 解析出的 com.x.Class#method）时，先按完整 className 精确匹配，
     * 只有找不到才退回简单名 / 纯方法名，避免 get/save 这类常见方法名跨类过度匹配。
     */
    public List<Long> resolve(String targetSymbolName) {
        if (!StringUtils.hasText(targetSymbolName)) {
            return List.of();
        }
        String target = targetSymbolName.trim();
        if (target.contains("#")) {
            String[] parts = target.split("#", 2);
            String classPart = parts[0];
            String method = parts[1];
            List<Long> fq = byFullClassHashMethod.get((classPart.trim() + "#" + method).toLowerCase(Locale.ROOT));
            if (fq != null && !fq.isEmpty()) {
                return cap(fq);
            }
            List<Long> simple = byClassHashMethod.get((simpleName(classPart) + "#" + method).toLowerCase(Locale.ROOT));
            if (simple != null && !simple.isEmpty()) {
                return cap(simple);
            }
            return cap(byMethodName.getOrDefault(method.toLowerCase(Locale.ROOT), List.of()));
        }
        String method = simpleName(target); // 取最后一段（去掉 scope. 前缀）
        return cap(byMethodName.getOrDefault(method.toLowerCase(Locale.ROOT), List.of()));
    }

    /**
     * 高置信度（AST 解析）依赖的解析：优先返回类限定的单个最佳匹配，避免扇出到所有同名方法。
     * 若存在全限定类#方法匹配，取其单个最佳（重载时取首个）；否则退回简单类#方法的单个最佳；
     * 两者都没有时才退回常规 resolve（可能是全限定名但类不在索引内 / 老数据）。
     */
    public List<Long> resolveBest(String targetSymbolName) {
        if (!StringUtils.hasText(targetSymbolName)) {
            return List.of();
        }
        String target = targetSymbolName.trim();
        if (target.contains("#")) {
            String[] parts = target.split("#", 2);
            String classPart = parts[0];
            String method = parts[1];
            List<Long> fq = byFullClassHashMethod.get((classPart.trim() + "#" + method).toLowerCase(Locale.ROOT));
            if (fq != null && !fq.isEmpty()) {
                return List.of(fq.get(0));
            }
            List<Long> simple = byClassHashMethod.get((simpleName(classPart) + "#" + method).toLowerCase(Locale.ROOT));
            if (simple != null && !simple.isEmpty()) {
                return List.of(simple.get(0));
            }
        }
        return resolve(targetSymbolName);
    }

    private static String simpleName(String qualified) {
        if (!StringUtils.hasText(qualified)) {
            return "";
        }
        String q = qualified.trim();
        int dot = q.lastIndexOf('.');
        return dot >= 0 ? q.substring(dot + 1) : q;
    }

    private static List<Long> cap(List<Long> ids) {
        return ids.stream().filter(Objects::nonNull).distinct()
                .limit(MAX_MATCHES).toList();
    }
}
