package com.repolens.kernel.git;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 极简 git 封装——支撑「直接改工作目录 + git 回溯」模式（去影子区后的撤销骨架）。
 *
 * <p>直接编辑模式下 agent 不再走影子区隔离，而是直接在用户打开的工作目录里改文件；
 * 撤销/回溯全靠 git：
 * <ul>
 *   <li>{@link #ensureBaseline}：导入/首次跑 agent 时，若目录不是 git 仓库就 {@code git init} + 建一次基线
 *       提交，保证「任何时候都能 git 回溯」这条安全网成立（git clone 来的仓库天生是 git 仓库，跳过）；</li>
 *   <li>{@link #showHead}：取某文件在 HEAD 的内容，作为实时 diff 的 before（工作树相对 HEAD 的改动，
 *       正是 Cursor/Claude Code 的展示模型）；</li>
 *   <li>{@link #restoreFile}：把某文件恢复到 HEAD 版本（逐文件撤销 = {@code git checkout -- <file>}）。</li>
 * </ul>
 * 全程 fail-safe：git 不可用/命令失败只 log，不抛断主链路。
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    /** 基线提交默认忽略的构建产物/依赖目录（避免把 target、node_modules 之类塞进基线）。 */
    private static final String DEFAULT_GITIGNORE = String.join("\n",
            "# RepoLens 自动基线忽略（可自行增删）",
            "target/", "build/", "dist/", "out/", ".gradle/",
            "node_modules/", "__pycache__/", ".rk/", ".idea/", ".vscode/", "*.log", "");

    /** 目录是否已是 git 仓库（含 .git）。 */
    public boolean isRepo(Path repoDir) {
        return repoDir != null && Files.isDirectory(repoDir.resolve(".git"));
    }

    /**
     * 保证工作目录有 git 基线：已是 git 仓库则不动（尊重用户自己的 git 状态）；否则 init + 写默认 .gitignore +
     * 建一次基线提交。失败只 log（直接编辑仍可用，只是少了 git 撤销）。
     */
    public void ensureBaseline(Path repoDir) {
        if (repoDir == null || !Files.isDirectory(repoDir) || isRepo(repoDir)) {
            return;
        }
        try {
            run(repoDir, 20, "git", "init");
            Path ignore = repoDir.resolve(".gitignore");
            if (!Files.exists(ignore)) {
                Files.writeString(ignore, DEFAULT_GITIGNORE, StandardCharsets.UTF_8);
            }
            // 基线提交需要 user.name/email；用仓库级配置兜底，避免全局未配置时 commit 失败。
            run(repoDir, 10, "git", "config", "user.email", "agent@repolens.local");
            run(repoDir, 10, "git", "config", "user.name", "RepoLens Agent");
            run(repoDir, 120, "git", "add", "-A");
            int code = run(repoDir, 120, "git", "commit", "-m", "RepoLens baseline");
            log.info("[git] 为工作目录建立 git 基线（code={}）: {}", code, repoDir);
        } catch (Exception e) {
            log.warn("[git] 建立 git 基线失败（直接编辑仍可用，撤销需自行处理）: {}", e.getMessage());
        }
    }

    /** 取某文件在 HEAD 的内容；文件在 HEAD 不存在（新增文件）或非 git 仓库返回 {@code null}。 */
    public String showHead(Path repoDir, String relPath) {
        if (repoDir == null || relPath == null || !isRepo(repoDir)) {
            return null;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", repoDir.toString(), "show", "HEAD:" + relPath)
                    .redirectErrorStream(false);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            p.getErrorStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把某文件恢复到 HEAD 版本（逐文件撤销）；HEAD 没有该文件（本次新建）则删除它。返回是否成功。 */
    public boolean restoreFile(Path repoDir, String relPath) {
        if (repoDir == null || relPath == null || !isRepo(repoDir)) {
            return false;
        }
        try {
            int code = run(repoDir, 15, "git", "checkout", "HEAD", "--", relPath);
            if (code == 0) {
                return true;
            }
            // HEAD 没有该文件 → 本次新建的，直接删除即恢复。
            Files.deleteIfExists(repoDir.resolve(relPath).normalize());
            return true;
        } catch (Exception e) {
            log.warn("[git] 恢复文件 {} 失败: {}", relPath, e.getMessage());
            return false;
        }
    }

    private int run(Path cwd, long timeoutSec, String... argv) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }
}
