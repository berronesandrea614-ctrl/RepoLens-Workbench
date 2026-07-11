package com.repolens.kernel.slash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 自定义 slash 命令（M7.4，最小真实）：读 repoDir 下 {@code .claude/commands/*.md}，把 {@code /name ...}
 * 展开为该命令 markdown 的正文提示（{@code $ARGUMENTS} 占位符替换为用户附带参数）。
 *
 * <p>这是<b>真读文件、真展开</b>的骨架：一个 {@code .claude/commands/foo.md} 定义命令 {@code /foo}。
 * 用户输入 {@code /foo 参数} → 本服务返回 md 正文（{@code $ARGUMENTS} 换成「参数」），供上层作为
 * 用户 prompt 喂进 loop。命令文件不存在 → 返回 null（上层按普通 prompt 处理）。
 *
 * <p>取舍：只做「命令定义 = markdown 正文模板 + $ARGUMENTS 替换」这一核心展开，未做 frontmatter/
 * 命名空间/allowed-tools 等高级特性——诚实标注为后续。但本骨架非空壳：它真扫描目录、真按名读文件、
 * 真替换参数并产出可直接喂 loop 的提示串。
 */
@Component("kernelSlashCommandService")
public class SlashCommandService {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandService.class);

    /** 相对 repoDir 的命令目录。 */
    public static final String COMMANDS_DIR = ".claude/commands";

    /** 参数占位符：命令正文里的 {@code $ARGUMENTS} 会被替换为用户附带参数。 */
    private static final String ARGS_PLACEHOLDER = "$ARGUMENTS";

    /**
     * 若输入是已定义的 slash 命令则展开为提示串，否则返回 null。
     *
     * @param input   用户原始输入（形如 {@code /review src/A.java} 或普通文本）
     * @param repoDir 仓库根（命令目录锚点）；空则不展开
     * @return 展开后的提示；非命令 / 未定义 / 读失败返回 null
     */
    public String expand(String input, Path repoDir) {
        if (input == null || repoDir == null) {
            return null;
        }
        String trimmed = input.strip();
        if (!trimmed.startsWith("/") || trimmed.length() < 2) {
            return null;
        }
        // 拆 /name 与其余参数
        int sp = indexOfWhitespace(trimmed);
        String name = (sp < 0 ? trimmed.substring(1) : trimmed.substring(1, sp)).strip();
        String arguments = sp < 0 ? "" : trimmed.substring(sp + 1).strip();
        if (name.isEmpty() || !isSafeName(name)) {
            return null;
        }

        Path cmdFile = repoDir.resolve(COMMANDS_DIR).resolve(name + ".md");
        if (!Files.isRegularFile(cmdFile)) {
            return null;
        }
        try {
            String body = Files.readString(cmdFile);
            return body.replace(ARGS_PLACEHOLDER, arguments);
        } catch (Exception e) {
            log.warn("[slash] 读取命令 {} 失败：{}", cmdFile, e.getMessage());
            return null;
        }
    }

    /** 发现所有已定义命令：name → 命令文件路径（供上层列命令用）。 */
    public Map<String, Path> discover(Path repoDir) {
        Map<String, Path> out = new LinkedHashMap<>();
        if (repoDir == null) {
            return out;
        }
        Path dir = repoDir.resolve(COMMANDS_DIR);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .forEach(p -> {
                        String fn = p.getFileName().toString();
                        out.put(fn.substring(0, fn.length() - ".md".length()), p);
                    });
        } catch (Exception e) {
            log.warn("[slash] 扫描命令目录 {} 失败：{}", dir, e.getMessage());
        }
        return out;
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 命令名只允许字母/数字/下划线/连字符，防路径穿越。 */
    private static boolean isSafeName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }
}
