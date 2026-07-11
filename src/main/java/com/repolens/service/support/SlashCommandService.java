package com.repolens.service.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slash 命令扩展：扫描 .repolens/commands/*.md，
 * 解析 frontmatter（name/description/argument-hint），
 * 用户输入 /cmd 时展开为完整 prompt。
 */
@Slf4j
@Component
public class SlashCommandService {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL);

    /**
     * 如果 userInput 以 / 开头，尝试匹配已注册的命令并展开。
     * @return 展开后的完整 prompt（替换了 $ARGUMENTS 占位符），
     *         不匹配时返回 empty
     */
    public Optional<String> expand(String userInput, Path repoRoot) {
        if (userInput == null || !userInput.startsWith("/")) {
            return Optional.empty();
        }
        String[] parts = userInput.split("\\s+", 2);
        String cmdName = parts[0].substring(1); // 去掉 /
        String args = parts.length > 1 ? parts[1] : "";

        Path commandsDir = repoRoot.resolve(".repolens/commands");
        if (!Files.isDirectory(commandsDir)) {
            return Optional.empty();
        }

        try (var stream = Files.list(commandsDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .map(p -> parseCommand(p, cmdName, args))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        } catch (IOException e) {
            log.warn("SlashCommandService: scan commands dir failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> parseCommand(Path file, String cmdName, String args) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = FRONTMATTER.matcher(content);
            if (!m.find()) return Optional.empty();

            Map<String, String> fm = parseFrontmatter(m.group(1));
            String name = fm.get("name");
            if (!cmdName.equals(name)) return Optional.empty();

            String body = m.group(2).trim();
            body = body.replace("$ARGUMENTS", args);
            return Optional.of(body);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Map<String, String> parseFrontmatter(String yaml) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : yaml.split("\n")) {
            String[] kv = line.split(":", 2);
            if (kv.length == 2) {
                map.put(kv[0].trim(), kv[1].trim());
            }
        }
        return map;
    }
}
