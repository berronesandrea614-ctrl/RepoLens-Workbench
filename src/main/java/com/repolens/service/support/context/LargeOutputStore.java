package com.repolens.service.support.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Component
public class LargeOutputStore {

    private static final int THRESHOLD_CHARS = 50_000;
    private static final int PREVIEW_CHARS = 2_000;
    private static final Path BLOB_DIR = Path.of(System.getProperty("user.home"), ".repolens", "context-blobs");

    public record StoreResult(boolean stored, String content, String ref) {}

    public StoreResult maybeStore(String content) {
        if (content == null || content.length() <= THRESHOLD_CHARS) {
            return new StoreResult(false, content, null);
        }
        try {
            Files.createDirectories(BLOB_DIR);
            String ref = UUID.randomUUID() + ".ctx";
            Path file = BLOB_DIR.resolve(ref);
            Files.writeString(file, content);
            String preview = content.substring(0, Math.min(PREVIEW_CHARS, content.length()));
            String stored = "[大输出已转磁盘] 前 " + PREVIEW_CHARS + " 字符预览：\n" + preview
                    + "\n...\n[完整内容在 context-blob:" + ref + "，用 read_context_blob 读取]";
            return new StoreResult(true, stored, ref);
        } catch (IOException e) {
            log.warn("LargeOutputStore: write failed (fail-safe, keeping inline): {}", e.getMessage());
            return new StoreResult(false, content, null);
        }
    }

    public String readBlob(String ref, int offset, int length) {
        try {
            Path file = BLOB_DIR.resolve(ref);
            if (!file.startsWith(BLOB_DIR)) throw new IllegalArgumentException("Invalid ref");
            String content = Files.readString(file);
            int from = Math.max(0, offset);
            int to = Math.min(content.length(), from + Math.max(1, length));
            return content.substring(from, to);
        } catch (Exception e) {
            return "read_context_blob error: " + e.getMessage();
        }
    }
}
