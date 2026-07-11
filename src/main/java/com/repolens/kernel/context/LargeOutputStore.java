package com.repolens.kernel.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * L0 大输出转磁盘存储（M6）。单条 tool_result 超阈值时，把<b>完整原文</b>落磁盘到
 * {@code <repoDir>/.rk/large-output/<hash>.txt}，消息里只留 head/tail 预览 + overflowRef。
 *
 * <p>为什么落磁盘而非丢弃：agent 后续可能要精确回看某段大输出（用 read/bash 按 ref 取回），
 * 不能真删；但塞进上下文会挤爆窗口。内容寻址（sha-256）→ 同一输出只存一份、ref 稳定可复算。
 *
 * <p>repoDir 为空（无真目录的极简场景）时降级到系统临时目录，保证机制始终可用、E2E 可断言。
 */
@Component("kernelLargeOutputStore")
public class LargeOutputStore {

    private static final Logger log = LoggerFactory.getLogger(LargeOutputStore.class);

    public static final String DIR = ".rk/large-output";

    /** 落盘一段大输出，返回内容寻址句柄（含磁盘绝对路径与 ref 短标识）。 */
    public Stored store(Path repoDir, String content) {
        String hash = sha256(content == null ? "" : content);
        Path base = baseDir(repoDir);
        Path file = base.resolve(hash + ".txt");
        try {
            Files.createDirectories(base);
            // 内容寻址：同 hash 已存在则不重复写（幂等、ref 稳定）
            if (!Files.exists(file)) {
                Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[L0] 大输出落盘失败 hash={}: {}", hash, e.getMessage());
        }
        return new Stored(hash, file);
    }

    private Path baseDir(Path repoDir) {
        if (repoDir != null) {
            return repoDir.resolve(DIR);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "rk-large-output");
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 32);
        } catch (Exception e) {
            // 理论不达；退化为长度+hashCode，仍稳定
            return "len" + (s == null ? 0 : s.length()) + "_" + Integer.toHexString(s == null ? 0 : s.hashCode());
        }
    }

    /** 落盘结果：内容 hash + 磁盘绝对路径。 */
    public record Stored(String hash, Path file) {
    }
}
