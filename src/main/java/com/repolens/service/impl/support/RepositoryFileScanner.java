package com.repolens.service.impl.support;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;

@Slf4j
public class RepositoryFileScanner {

    private static final int SAMPLE_BYTES = 4096;
    private static final Set<String> SKIPPED_DIRECTORY_NAMES = Set.of(
            ".git", "target", "build", "out", "node_modules", "logs", ".idea", ".vscode", "dist"
    );
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "svg", "tif", "tiff"
    );
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "jar", "war", "class", "zip", "gz", "7z", "tar", "rar", "so", "dll", "exe", "bin", "dat", "pdf"
    );

    private final long maxFileSizeBytes;

    public RepositoryFileScanner(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public ScanSummary scan(Path repoRoot, ScannedFileHandler handler) throws IOException {
        Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot)) {
            throw new IOException("Repository directory does not exist: " + normalizedRoot);
        }

        ScanSummary summary = new ScanSummary();
        Files.walkFileTree(normalizedRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                Path normalizedDir = dir.toAbsolutePath().normalize();
                if (!normalizedDir.startsWith(normalizedRoot)) {
                    throw new SecurityException("Directory escapes repository root: " + normalizedDir);
                }
                if (!normalizedDir.equals(normalizedRoot)) {
                    String name = normalizedDir.getFileName() == null ? "" : normalizedDir.getFileName().toString();
                    if (SKIPPED_DIRECTORY_NAMES.contains(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }

                summary.scannedFileCount++;
                try {
                    if (shouldSkipFile(file, attrs)) {
                        summary.skippedFileCount++;
                        return FileVisitResult.CONTINUE;
                    }

                    ScannedFile scannedFile = buildScannedFile(normalizedRoot, file);
                    boolean saved = handler.handle(scannedFile);
                    if (saved) {
                        summary.savedFileCount++;
                    } else {
                        summary.skippedFileCount++;
                    }
                } catch (Exception ex) {
                    summary.skippedFileCount++;
                    log.warn("Skip file due to scan error, file={}, reason={}", file, ex.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return summary;
    }

    private boolean shouldSkipFile(Path file, BasicFileAttributes attrs) throws IOException {
        if (attrs.size() > maxFileSizeBytes) {
            return true;
        }

        String extension = extension(file);
        if (IMAGE_EXTENSIONS.contains(extension) || BINARY_EXTENSIONS.contains(extension)) {
            return true;
        }

        byte[] sampleBytes = readSample(file, SAMPLE_BYTES);
        return isLikelyBinary(sampleBytes);
    }

    private ScannedFile buildScannedFile(Path repoRoot, Path file) throws IOException {
        Path normalizedFile = file.toAbsolutePath().normalize();
        if (!normalizedFile.startsWith(repoRoot)) {
            throw new SecurityException("File escapes repository root: " + normalizedFile);
        }

        byte[] bytes = Files.readAllBytes(normalizedFile);
        String relativePath = repoRoot.relativize(normalizedFile).toString().replace('\\', '/');
        String fileType = toFileType(extension(normalizedFile));
        String hash = sha256(bytes);
        int lineCount = countLines(bytes);
        return new ScannedFile(relativePath, fileType, hash, lineCount);
    }

    private byte[] readSample(Path file, int sampleSize) throws IOException {
        byte[] buffer = new byte[sampleSize];
        try (InputStream inputStream = Files.newInputStream(file)) {
            int read = inputStream.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            byte[] sample = new byte[read];
            System.arraycopy(buffer, 0, sample, 0, read);
            return sample;
        }
    }

    private boolean isLikelyBinary(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        int suspicious = 0;
        for (byte b : bytes) {
            int value = b & 0xFF;
            if (value == 0) {
                return true;
            }
            boolean isAllowedControl = value == 0x09 || value == 0x0A || value == 0x0D;
            if (value < 0x20 && !isAllowedControl) {
                suspicious++;
            }
        }
        return suspicious > bytes.length / 10;
    }

    private String extension(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private String toFileType(String extension) {
        return switch (extension) {
            case "java" -> "JAVA";
            case "xml" -> "XML";
            case "yml", "yaml" -> "YAML";
            case "properties" -> "PROPERTIES";
            case "md" -> "MARKDOWN";
            case "sql" -> "SQL";
            default -> "TEXT";
        };
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private int countLines(byte[] bytes) {
        if (bytes.length == 0) {
            return 0;
        }
        int lineCount = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                lineCount++;
            }
        }
        if (bytes[bytes.length - 1] != '\n') {
            lineCount++;
        }
        return lineCount;
    }

    @Getter
    public static class ScanSummary {
        private int scannedFileCount;
        private int savedFileCount;
        private int skippedFileCount;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ScannedFile {
        private final String relativePath;
        private final String fileType;
        private final String contentHash;
        private final Integer lineCount;
    }

    @FunctionalInterface
    public interface ScannedFileHandler {
        boolean handle(ScannedFile scannedFile) throws Exception;
    }
}
