package com.repolens.kernel.hook;

import com.repolens.kernel.spi.ToolContext;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 受保护路径写拦截 Hook（M7.1 唯一的真 hook）：确定性拦截对<b>密钥/敏感配置类路径</b>的任何写入。
 *
 * <p>拦截的写工具：{@code write / edit / multi_edit / create}。拦截的目标路径（大小写不敏感，按文件名/后缀匹配）：
 * {@code .env}、{@code .env.*}、{@code *.pem}、{@code *.key}、{@code id_rsa}、{@code credentials}、
 * {@code .npmrc}、{@code .pypirc}、{@code .netrc}、{@code secrets.*} 等。命中即 BLOCK，工具不执行，
 * 拦截理由回填给 LLM 当 observation。
 *
 * <p>这是与权限门（M4，按风险档位/模式做概率性策略裁决）互补的<b>确定性护栏</b>：
 * 影子区隔离写在权限门里是「C 级放行」的，但写 {@code .env} 这类密钥文件属于策略之外的红线，
 * 由本 hook 硬拦——不管什么模式（含 BYPASS 之外的常规模式）都不许写。
 */
@Component
public class ProtectedPathWriteHook implements PreToolUseHook {

    /** 会往文件写内容的工具名（小写归一后匹配）。 */
    private static final Set<String> WRITE_TOOLS = Set.of("write", "edit", "multi_edit", "create");

    /** 精确匹配的敏感文件名。 */
    private static final Set<String> PROTECTED_NAMES = Set.of(
            ".env", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            "credentials", ".npmrc", ".pypirc", ".netrc", ".htpasswd");

    /** 前缀匹配（如 .env.local / .env.production / secrets.yml）。 */
    private static final Set<String> PROTECTED_PREFIXES = Set.of(".env.", "secrets.", "secret.");

    /** 后缀匹配（密钥/证书类）。 */
    private static final Set<String> PROTECTED_SUFFIXES = Set.of(".pem", ".key", ".p12", ".pfx", ".keystore");

    @Override
    public HookDecision beforeToolUse(String toolName, Map<String, Object> args, ToolContext ctx) {
        if (toolName == null || !WRITE_TOOLS.contains(toolName.toLowerCase(Locale.ROOT))) {
            return HookDecision.proceed();
        }
        String path = str(args, "file_path");
        if (path == null || path.isBlank()) {
            return HookDecision.proceed();
        }
        if (isProtected(path)) {
            return HookDecision.block(
                    "受保护路径写入被 ProtectedPathWriteHook 拦截：禁止向密钥/敏感配置文件 "
                    + "（" + path + "）写入。这类文件（.env / *.key / *.pem / credentials 等）"
                    + "不应由 agent 修改，请改动应用代码或非敏感配置，切勿把密钥写入仓库。");
        }
        return HookDecision.proceed();
    }

    /** 按文件名（路径最后一段）判断是否受保护，大小写不敏感。 */
    static boolean isProtected(String path) {
        String norm = path.replace('\\', '/');
        String name = norm.substring(norm.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return false;
        }
        if (PROTECTED_NAMES.contains(name)) {
            return true;
        }
        for (String p : PROTECTED_PREFIXES) {
            if (name.startsWith(p)) {
                return true;
            }
        }
        for (String s : PROTECTED_SUFFIXES) {
            if (name.endsWith(s)) {
                return true;
            }
        }
        return false;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
