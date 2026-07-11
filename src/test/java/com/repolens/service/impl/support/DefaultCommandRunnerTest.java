package com.repolens.service.impl.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DefaultCommandRunner 单测：验证超时、截断、退出码、失败启动。
 * 注意：这些测试不跑真实 mvn/npm（仅用简单系统命令），不依赖网络或 repo。
 */
class DefaultCommandRunnerTest {

    @TempDir
    Path workDir;

    private final DefaultCommandRunner runner = new DefaultCommandRunner();

    @Test
    void run_successfulCommand_returnsZeroExitCode() {
        // 'echo' 是所有 POSIX 系统都有的命令
        CommandRunner.RunResult result = runner.run(
                new String[]{"echo", "hello"}, workDir, 5000);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.outputTail()).contains("hello");
    }

    @Test
    void run_nonZeroExitCode_isReturnedCorrectly() {
        // 'false' 命令总是返回退出码 1
        CommandRunner.RunResult result = runner.run(
                new String[]{"false"}, workDir, 5000);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void run_timeout_marksTimedOutAndKillsProcess() throws Exception {
        // 用 sleep 命令，设置超短超时
        CommandRunner.RunResult result = runner.run(
                new String[]{"sleep", "60"}, workDir, 300);

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(-1);
    }

    @Test
    void run_longOutput_truncatesToTail() throws Exception {
        // 产生一个脚本：输出超过 OUTPUT_TAIL_CHARS（8000）字符
        Path script = workDir.resolve("gen.sh");
        Files.writeString(script,
                "#!/bin/sh\n" +
                "for i in $(seq 1 500); do echo \"line $i: " + "x".repeat(30) + "\"; done\n");
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));

        CommandRunner.RunResult result = runner.run(
                new String[]{script.toAbsolutePath().toString()}, workDir, 10000);

        assertThat(result.exitCode()).isEqualTo(0);
        // outputTail 不超过限制
        assertThat(result.outputTail().length()).isLessThanOrEqualTo(DefaultCommandRunner.OUTPUT_TAIL_CHARS);
        // 尾部应含最后几行
        assertThat(result.outputTail()).contains("line 500");
    }

    @Test
    void run_invalidCommand_returnsFailure() {
        // 不存在的命令 → 启动失败
        CommandRunner.RunResult result = runner.run(
                new String[]{"this_command_does_not_exist_ever_12345"}, workDir, 5000);

        // exitCode 为 -1 表示启动失败
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.timedOut()).isFalse();
        assertThat(result.outputTail()).contains("Failed to start");
    }

    @Test
    void run_prependsCommonPathsToEnvironment() {
        // Run a command that prints the PATH; verify it contains the expected extra paths.
        CommandRunner.RunResult result = runner.run(
                new String[]{"sh", "-c", "echo $PATH"}, workDir, 5000);

        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.outputTail()).contains("/opt/homebrew/bin");
        assertThat(result.outputTail()).contains("/usr/local/bin");
    }
}
