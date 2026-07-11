package com.repolens.service.impl.support;

import java.nio.file.Path;

/**
 * 可 mock 的命令执行抽象：把 ProcessBuilder 封装成接口，让 runVerification 单测不真跑 mvn。
 * 真实实现 {@link DefaultCommandRunner}；测试注入 mock。
 */
public interface CommandRunner {

    /**
     * 在指定工作目录运行命令（显式参数数组，绝不走 shell -c）。
     *
     * @param command    命令及参数数组（ProcessBuilder 直接传递，不经 shell 解析）
     * @param workDir    命令工作目录
     * @param timeoutMs  最大等待毫秒；超时后 destroyForcibly
     * @return           执行结果
     */
    RunResult run(String[] command, Path workDir, long timeoutMs);

    /**
     * 命令执行结果：退出码、是否超时、输出尾部（stdout+stderr 合并，最多 8000 字符）。
     */
    record RunResult(int exitCode, boolean timedOut, String outputTail) {
    }
}
