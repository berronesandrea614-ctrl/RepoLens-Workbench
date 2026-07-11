package com.repolens.security.permission;

import com.repolens.domain.entity.PermissionRuleEntity;
import com.repolens.mapper.PermissionRuleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 权限引擎：deny > ask > allow 三表体系。
 * 规则用意图/类别而非命令字符串（避免 238 条白名单膨胀）。
 * 无匹配时：写默认 ASK，读默认 ALLOW。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionEngine {

    private final PermissionRuleMapper permissionRuleMapper;

    public enum EvalResult { ALLOW, ASK, DENY }

    private static final Set<String> PROTECTED_PATHS = Set.of(
            ".git", ".claude", ".env", "application.yml", "pom.xml", "secret", ".key"
    );

    private static final Set<String> WRITE_CATEGORIES = Set.of("WRITE", "CREATE", "DELETE", "EXEC");

    /**
     * 评估工具操作权限。
     * @param category  操作类别（READ/WRITE/CREATE/DELETE/EXEC/NETWORK）
     * @param targetPath 目标路径（可为 null）
     * @return ALLOW | ASK | DENY
     */
    public EvalResult evaluate(String category, String targetPath) {
        try {
            // 受保护路径强制 ASK（即使有 allow 规则也不自动放行）
            if (isProtected(targetPath)) return EvalResult.ASK;

            // 查规则表：按 rule_order 升序，首匹配胜
            var rules = permissionRuleMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<PermissionRuleEntity>()
                            .eq("category", category)
                            .orderByAsc("rule_order"));
            for (var rule : rules) {
                if (matchesPattern(rule.getPattern(), targetPath)) {
                    return switch (rule.getDecision()) {
                        case "deny" -> EvalResult.DENY;
                        case "ask" -> EvalResult.ASK;
                        case "allow" -> EvalResult.ALLOW;
                        default -> EvalResult.ASK;
                    };
                }
            }
            // 无匹配：写默认 ASK，读默认 ALLOW
            return isWriteCategory(category) ? EvalResult.ASK : EvalResult.ALLOW;
        } catch (Exception e) {
            log.warn("PermissionEngine.evaluate failed (fail-closed ASK): {}", e.getMessage());
            return EvalResult.ASK; // fail-closed
        }
    }

    private boolean isProtected(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String p : PROTECTED_PATHS) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    private boolean matchesPattern(String pattern, String target) {
        if (pattern == null || pattern.equals("*")) return true;
        if (target == null) return false;
        String regex = pattern.replace(".", "\\.").replace("*", ".*");
        return target.matches(".*" + regex + ".*");
    }

    private boolean isWriteCategory(String cat) {
        return WRITE_CATEGORIES.contains(cat);
    }
}
