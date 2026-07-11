package com.repolens.kernel.context;

import com.repolens.llm.model.LlmMessage;
import com.repolens.llm.model.ToolCall;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * L3 会话笔记（session-memory）。从滚动对话里抽取<b>关键事实</b>并累积：改了哪些文件、
 * 验证结果、决定/约束。这些事实在 L4 全量摘要时被保留、用于填充 8 段模板，
 * 使「压缩后仍知道自己做过什么、下一步是什么」。
 *
 * <p>抽取是<b>确定性、无 LLM</b> 的：从 assistant 的 tool_calls（write/edit/multi_edit 的 file_path）
 * 与 tool_result 文本里的验证信号（编译通过/失败、测试）扫出，随对话增量更新。不依赖模型二次调用，
 * 离线可跑、E2E 可断言。
 */
public final class SessionMemory {

    /** 首个 user 消息视为任务陈述。 */
    private String task;
    /** 改动过的文件（去重、保序）。 */
    private final Set<String> changedFiles = new LinkedHashSet<>();
    /** 最近一次可辨识的验证状态。 */
    private String verifyStatus;
    /** 记录到的未决/失败信号。 */
    private final Set<String> openQuestions = new LinkedHashSet<>();

    /** 从当前消息历史增量抽取关键事实（幂等、只累加，不改历史）。 */
    public void observe(List<LlmMessage> messages) {
        if (messages == null) {
            return;
        }
        for (LlmMessage m : messages) {
            String role = m.getRole();
            if (task == null && "user".equals(role) && m.getContent() != null && !m.getContent().isBlank()) {
                task = firstLine(m.getContent(), 200);
            }
            if ("assistant".equals(role) && m.getToolCalls() != null) {
                for (ToolCall tc : m.getToolCalls()) {
                    recordFileMutation(tc);
                }
            }
            if ("tool".equals(role) && m.getContent() != null) {
                recordVerification(m.getContent());
            }
        }
    }

    private void recordFileMutation(ToolCall tc) {
        String name = tc.getName();
        if (name == null) {
            return;
        }
        boolean isWrite = name.equals("write") || name.equals("edit") || name.equals("multi_edit");
        if (!isWrite || tc.getArguments() == null) {
            return;
        }
        Object fp = tc.getArguments().get("file_path");
        if (fp != null) {
            changedFiles.add(String.valueOf(fp));
        }
    }

    private void recordVerification(String content) {
        String c = content.toLowerCase();
        if (c.contains("build success") || content.contains("编译通过") || content.contains("测试通过")
                || c.contains("verification passed") || c.contains("all tests passed")) {
            verifyStatus = "已验证通过（" + firstLine(content, 80) + "）";
        } else if (c.contains("build failure") || content.contains("编译失败") || content.contains("测试失败")
                || c.contains("verification failed") || c.contains("compilation error")) {
            verifyStatus = "验证失败（" + firstLine(content, 80) + "）";
            openQuestions.add("验证未通过，需修复：" + firstLine(content, 80));
        }
        if (content.contains("语法护栏")) {
            openQuestions.add("曾被语法护栏拒绝的改动需复核");
        }
    }

    // ---- L4 填充用的读取面 ----

    public String task() {
        return task;
    }

    public String changesJoined() {
        return changedFiles.isEmpty() ? null : "改动文件 " + String.join("、", changedFiles);
    }

    public String filesJoined() {
        return changedFiles.isEmpty() ? null : String.join("、", changedFiles);
    }

    public String verifyStatus() {
        return verifyStatus;
    }

    public String openQuestions() {
        return openQuestions.isEmpty() ? null : String.join("；", openQuestions);
    }

    public String constraints() {
        return null;
    }

    public String nextStep() {
        return null;
    }

    /** 供外部/测试查看已记录的改动文件集。 */
    public Set<String> changedFiles() {
        return changedFiles;
    }

    private static String firstLine(String s, int max) {
        int nl = s.indexOf('\n');
        String line = nl >= 0 ? s.substring(0, nl) : s;
        line = line.strip();
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }
}
