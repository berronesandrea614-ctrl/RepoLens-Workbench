package com.repolens.service.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 聚焦「无 .git 普通文件夹复制快照」分支的单元测试，避免 mock 重型 JGit clone。
 */
class GitRepositoryServiceImplTest {

    @Test
    void copyLocalSnapshotShouldCopyFilesAndSkipNoise(@TempDir Path src, @TempDir Path dst) throws Exception {
        GitRepositoryServiceImpl service = new GitRepositoryServiceImpl(null, null, null, null, null, null);
        ReflectionTestUtils.setField(service, "maxFileSizeBytes", 32L);

        // 普通代码文件（含中文目录）应被复制
        Files.createDirectories(src.resolve("个人项目"));
        Files.writeString(src.resolve("个人项目/App.java"), "class App {}");
        Files.writeString(src.resolve("README.md"), "hello");

        // 噪声目录应被跳过
        Files.createDirectories(src.resolve("node_modules/leftpad"));
        Files.writeString(src.resolve("node_modules/leftpad/index.js"), "module.exports=1;");
        Files.createDirectories(src.resolve(".git"));
        Files.writeString(src.resolve(".git/HEAD"), "ref: refs/heads/main");
        Files.createDirectories(src.resolve("target"));
        Files.writeString(src.resolve("target/App.class"), "binary");

        // 超限文件（> 32 字节）应被跳过
        Files.writeString(src.resolve("big.txt"), "this content is definitely larger than thirty-two bytes limit");

        service.copyLocalSnapshot(src, dst);

        Assertions.assertTrue(Files.exists(dst.resolve("个人项目/App.java")), "code file should be copied");
        Assertions.assertTrue(Files.exists(dst.resolve("README.md")), "root file should be copied");
        Assertions.assertFalse(Files.exists(dst.resolve("node_modules")), "node_modules should be skipped");
        Assertions.assertFalse(Files.exists(dst.resolve(".git")), ".git should be skipped");
        Assertions.assertFalse(Files.exists(dst.resolve("target")), "target should be skipped");
        Assertions.assertFalse(Files.exists(dst.resolve("big.txt")), "oversized file should be skipped");
    }
}
