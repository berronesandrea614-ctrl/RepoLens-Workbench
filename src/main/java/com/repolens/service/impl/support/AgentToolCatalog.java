package com.repolens.service.impl.support;

import com.repolens.llm.model.ToolDefinition;
import com.repolens.domain.enums.PermissionMode;
import com.repolens.service.support.EditFormatPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agentic 检索可用工具目录。
 * 把 ReadonlyToolService 的只读工具，描述成 function-calling 的 ToolDefinition，
 * 供 CodeAnswerService 在 agent loop 里交给 LLM 选择。
 *
 * 设计要点：
 * - 工具名与 ToolInvokeService 分发用的 toolName 完全一致；
 * - description 写精准，明确"什么时候用"，降低 LLM 选错工具概率；
 * - 只读工具（含 webFetch/listDirectory）ask+code 两种模式均可用；
 * - 写/执行/删除工具仅在 codeMode==true 时追加。
 */
@Component
public class AgentToolCatalog {

    /**
     * 写工具名单（安全边界）：只有编码模式的 agent 才注入这些工具；直连调试端点必须拒绝它们。
     * 供 {@code RepoController} 的 direct-invoke 端点做统一拒绝判断。
     */
    public static final Set<String> WRITE_TOOL_NAMES = Set.of(
            "writeFileContent",
            "editFileContent",
            "createFileContent",
            "deleteFile",
            "multiEditFile");

    /**
     * 执行工具名单（安全边界）：与写工具同等约束——code 模式 only + 直连端点拒绝。
     * 与 {@link #WRITE_TOOL_NAMES} 分开命名，方便测试与审计区分写/执行两类高危工具。
     */
    public static final Set<String> EXEC_TOOL_NAMES = Set.of("runVerification");

    private final List<ToolDefinition> readOnlyTools;
    private final ToolDefinition writeTool;
    private final ToolDefinition editTool;
    private final ToolDefinition createTool;
    private final ToolDefinition deleteTool;
    private final ToolDefinition verifyTool;
    private final ToolDefinition grepTool;
    private final ToolDefinition globTool;
    private final ToolDefinition multiEditTool;
    private final ToolDefinition todoWriteTool;
    private final ToolDefinition taskTool;
    private final ToolDefinition bashExecTool;
    private final ToolDefinition bashOutputTool;
    private final ToolDefinition killBashTool;
    private final ToolDefinition revisePlanTool;
    private final boolean bashEnabled;

    public AgentToolCatalog(
            @Value("${repolens.agent.web-fetch-enabled:true}") boolean webFetchEnabled,
            @Value("${repolens.agent.bash-enabled:false}") boolean bashEnabled) {
        this.bashEnabled = bashEnabled;
        this.writeTool = buildWriteTool();
        this.editTool = buildEditTool();
        this.createTool = buildCreateTool();
        this.deleteTool = buildDeleteTool();
        this.verifyTool = buildVerifyTool();
        this.grepTool = buildGrepTool();
        this.globTool = buildGlobTool();
        this.multiEditTool = buildMultiEditTool();
        this.todoWriteTool = buildTodoWriteTool();
        this.taskTool = buildTaskTool();
        this.bashExecTool = bashEnabled ? buildBashExecTool() : null;
        this.bashOutputTool = bashEnabled ? buildBashOutputTool() : null;
        this.killBashTool = bashEnabled ? buildKillBashTool() : null;
        this.revisePlanTool = buildRevisePlanTool();
        this.readOnlyTools = buildReadOnlyTools(webFetchEnabled);
    }

    /** 纯基础只读工具（不含 TodoWrite/Task 控制工具）。供子代理使用，防递归套娃。 */
    public List<ToolDefinition> baseReadOnlyTools() {
        return readOnlyTools;
    }

    /** 只读工具集（默认 ask 模式）。保持向后兼容：绝不含写工具。TodoWrite/Task 控制工具始终包含。 */
    public List<ToolDefinition> tools() {
        List<ToolDefinition> all = new ArrayList<>(readOnlyTools);
        all.add(todoWriteTool);
        all.add(taskTool);
        return all;
    }

    /**
     * 按模式返回工具集：只读工具恒有；写工具 + 执行工具仅在 codeMode==true 时追加。
     * 这是写/执行能力的第一道安全门——ask 模式下 LLM 根本拿不到任何写/执行工具。
     */
    public List<ToolDefinition> tools(boolean codeMode) {
        if (!codeMode) {
            return readOnlyTools;
        }
        List<ToolDefinition> all = new ArrayList<>(readOnlyTools);
        // 写工具：优先用 editFileContent 做最小改动，整文件重写才用 writeFileContent，
        // 新建用 createFileContent，删除用 deleteFile（staged 审批门，不直接删盘）。
        all.add(editTool);
        all.add(multiEditTool);
        all.add(writeTool);
        all.add(createTool);
        all.add(deleteTool);
        // 执行工具：验证编译/测试，已 apply 的改动才有意义。
        all.add(verifyTool);
        // 控制工具：TodoWrite + Task 始终可用。
        all.add(todoWriteTool);
        all.add(taskTool);
        all.add(revisePlanTool);
        if (bashEnabled) {
            all.add(bashExecTool);
            all.add(bashOutputTool);
            all.add(killBashTool);
        }
        return all;
    }

    public List<ToolDefinition> tools(PermissionMode mode) {
        if (mode == PermissionMode.PLAN) {
            List<ToolDefinition> planTools = new ArrayList<>(readOnlyTools);
            planTools.add(buildPlanStructuredV2Tool());
            planTools.add(todoWriteTool);
            planTools.add(taskTool);
            planTools.add(revisePlanTool);
            return planTools;
        }
        return tools(true);
    }

    public List<ToolDefinition> tools(boolean codeMode, EditFormatPolicy.Tier tier) {
        if (!codeMode) return readOnlyTools;
        List<ToolDefinition> all = new ArrayList<>(readOnlyTools);
        if (tier == EditFormatPolicy.Tier.STRONG) {
            all.add(editTool);
            all.add(multiEditTool);
        }
        all.add(writeTool);
        all.add(createTool);
        all.add(deleteTool);
        all.add(verifyTool);
        all.add(todoWriteTool);
        all.add(taskTool);
        all.add(revisePlanTool);
        if (bashEnabled) {
            all.add(bashExecTool);
            all.add(bashOutputTool);
            all.add(killBashTool);
        }
        return all;
    }

    private ToolDefinition buildWriteTool() {
        return tool("writeFileContent",
                "覆写仓库内【已存在】的文件为给定完整内容。"
                        + "仅用于需要整文件重写的场景；若只改少数行，优先用 editFileContent 做最小修改。"
                        + "写前应先用 getFileContent 读取现有内容。改动先暂存，需用户 Accept 后才写盘。",
                params(
                        Map.of(
                                "filePath", strProp("要覆写的文件相对路径（必须已存在）"),
                                "content", strProp("文件的完整新内容（整文件覆写）")
                        ),
                        List.of("filePath", "content")));
    }

    private ToolDefinition buildEditTool() {
        return tool("editFileContent",
                "str_replace 精准编辑仓库内【已存在】的文件。"
                        + "oldString 必须在文件中唯一出现（0 次→报错「未找到」，>1 次→报错「不唯一，请提供更多上下文」）。"
                        + "优先用此工具做最小必要修改，而非整文件重写。改动先暂存，需用户 Accept 后才写盘。",
                params(
                        Map.of(
                                "filePath", strProp("目标文件相对路径（必须已存在）"),
                                "oldString", strProp("要被替换的原字符串（必须在文件中唯一出现）"),
                                "newString", strProp("替换后的新字符串"),
                                "replaceAll", Map.of("type", "boolean",
                                        "description", "若 oldString 出现多次，传 true 全部替换")
                        ),
                        List.of("filePath", "oldString", "newString")));
    }

    private ToolDefinition buildCreateTool() {
        return tool("createFileContent",
                "在仓库内创建一个【不存在】的新文件。"
                        + "目标文件必须尚未存在（存在则报错，请改用 editFileContent 或 writeFileContent）。"
                        + "改动先暂存，需用户 Accept 后才写盘。",
                params(
                        Map.of(
                                "filePath", strProp("新文件的相对路径（不能已存在）"),
                                "content", strProp("新文件的完整内容")
                        ),
                        List.of("filePath", "content")));
    }

    private ToolDefinition buildDeleteTool() {
        return tool("deleteFile",
                "从仓库内删除一个【已存在】的文件（staged 审批门，不直接删盘）。"
                        + "删除操作先暂存（status=PROPOSED），需用户 Accept 后才真正删除磁盘文件；"
                        + "Reject 则取消删除。删除前确认此文件确实不再需要，操作不可轻易撤回。"
                        + "注意：只能删除普通文件，不能删目录。",
                params(
                        Map.of(
                                "filePath", strProp("要删除的文件相对路径（必须已存在的常规文件）")
                        ),
                        List.of("filePath")));
    }

    private ToolDefinition buildVerifyTool() {
        return tool("runVerification",
                "在仓库工作副本目录执行编译或测试以验证代码正确性。"
                        + "自动检测项目类型（pom.xml → Maven；package.json → npm），"
                        + "若 pom.xml/package.json 嵌套在子目录（最多 3 层），也能自动探测。"
                        + "注意：staged 改动尚未落盘——只有用户 Accept 改动后，验证才能反映新代码状态；"
                        + "PROPOSED 状态下验证反映的是改动前的代码。"
                        + "返回 {exitCode, timedOut, outputTail, buildDir}；exitCode==0 表示成功。",
                params(
                        Map.of(
                                "kind", strProp("验证类型：\"build\"（编译）或 \"test\"（运行测试）"),
                                "testFilter", strProp("（可选）Maven -Dtest 过滤器，仅在 kind=test 时生效，"
                                        + "只允许 [A-Za-z0-9_.*#,]+ 字符")
                        ),
                        List.of("kind")));
    }

    private List<ToolDefinition> buildReadOnlyTools(boolean webFetchEnabled) {
        List<ToolDefinition> list = new ArrayList<>();
        list.add(tool("searchCodeChunks",
                "按自然语言/关键词在当前仓库做代码片段检索（向量+关键词混合）。"
                        + "适合“某功能在哪实现/相关代码在哪”这类模糊定位，是最常用的起点工具。",
                params(
                        Map.of(
                                "query", strProp("检索用的自然语言或关键词"),
                                "topK", intProp("返回条数，默认 8，最大 20")
                        ),
                        List.of("query"))));
        list.add(tool("getFileContent",
                "读取指定文件在 [startLine, endLine] 行范围内的源码内容。"
                        + "在已知文件路径、需要看具体实现时使用。",
                params(
                        Map.of(
                                "filePath", strProp("文件相对路径"),
                                "startLine", intProp("起始行，从 1 开始"),
                                "endLine", intProp("结束行")
                        ),
                        List.of("filePath"))));
        list.add(tool("findApiByPath",
                "按 HTTP 接口路径（如 /user/create）定位对应的 Controller 方法。"
                        + "在问题涉及某个 REST 接口时使用。",
                params(
                        Map.of("apiPath", strProp("HTTP 接口路径，如 /user/create")),
                        List.of("apiPath"))));
        list.add(tool("findSymbolByName",
                "按类名或方法名精确查找符号（类/方法/API）及其位置。"
                        + "在已知一个具体类名或方法名、想定位定义时使用。",
                params(
                        Map.of("symbolName", strProp("类名或方法名")),
                        List.of("symbolName"))));
        list.add(tool("findMethodCallers",
                "查询某个方法被哪些地方调用（调用者）。在做影响面/谁依赖了它的分析时使用。",
                params(
                        Map.of("symbolName", strProp("方法名（必要时含类名）")),
                        List.of("symbolName"))));
        list.add(tool("findMethodCallees",
                "查询某个方法内部调用了哪些方法（被调用者）。在追一条调用链向下展开时使用。",
                params(
                        Map.of("symbolName", strProp("方法名（必要时含类名）")),
                        List.of("symbolName"))));
        list.add(tool("analyzeImpact",
                "基于调用关系做静态影响分析：改动某个类/方法可能波及哪些地方。"
                        + "在评估“改这里会影响什么”时使用。",
                params(
                        Map.of(
                                "className", strProp("类名"),
                                "methodName", strProp("方法名，可选；只给类名则分析整类")
                        ),
                        List.of("className"))));
        list.add(tool("listDirectory",
                "列出仓库内某目录下的直接子项（不递归）。"
                        + "返回 [{name, type:\"file\"|\"dir\", size?}]，按名字排序，最多 200 项。"
                        + "在创建/修改文件前先了解目录结构时使用；也可用于确认文件是否存在。",
                params(
                        Map.of(
                                "path", strProp("目录相对路径（空/\".\"/不填 = 仓库根）")
                        ),
                        List.of())));
        list.add(tool("readFile",
                "带 offset/limit 和行号前缀读取文件内容（cat -n 风格）。"
                + "offset=起始行(1-based), limit=行数(默认2000)。"
                + "返回内容每行前缀行号：\"  1\\t内容\"。"
                + "适合大文件分段读取。",
                params(
                        Map.of(
                                "filePath", strProp("文件相对路径"),
                                "offset", intProp("起始行，从 1 开始，默认 1"),
                                "limit", intProp("最多读取行数，默认 2000，最大 5000")
                        ),
                        List.of("filePath"))));
        list.add(grepTool);
        list.add(globTool);
        if (webFetchEnabled) {
            list.add(tool("webFetch",
                    "抓取外部 HTTP/HTTPS URL 的页面或文档内容。"
                            + "HTML 自动剥标签成纯文本；JSON/纯文本原样返回。"
                            + "适合查官方文档、API 参考、Release Notes 等外部资源。"
                            + "禁止访问内网/localhost（SSRF 保护）；只支持 http/https。"
                            + "返回 {url, status, contentType, truncated, content}。",
                    params(
                            Map.of(
                                    "url", strProp("要抓取的完整 URL（必须是 http:// 或 https://）"),
                                    "maxChars", intProp("返回内容字符上限，默认 20000，最大 40000")
                            ),
                            List.of("url"))));
        }
        return List.copyOf(list);
    }

    private ToolDefinition tool(String name, String description, Map<String, Object> parameters) {
        return ToolDefinition.builder()
                .name(name)
                .description(description)
                .parameters(parameters)
                .build();
    }

    private Map<String, Object> params(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> strProp(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        return prop;
    }

    private Map<String, Object> intProp(String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "integer");
        prop.put("description", description);
        return prop;
    }

    private ToolDefinition buildGrepTool() {
        return tool("grepCode",
                "在仓库源码中做字面量字符串搜索（非正则）。"
                + "优先用 ripgrep，不可用时降级 Java 实现；遵守 .gitignore，最多返回 200 条 / 32k 字符。"
                + "三种模式：files（只返回文件名）/ context（返回匹配行+上下2行）/ count（每文件匹配数）。"
                + "只读工具，ask+code 均可用。",
                params(
                        Map.of(
                                "pattern", strProp("要搜索的字面量字符串（不解释为正则）"),
                                "glob", strProp("（可选）文件 glob 过滤，如 *.java"),
                                "mode", strProp("返回模式：files | context | count，默认 context"),
                                "caseInsensitive", Map.of("type", "boolean",
                                        "description", "是否忽略大小写，默认 false")
                        ),
                        List.of("pattern")));
    }

    private ToolDefinition buildGlobTool() {
        return tool("globFiles",
                "按 glob 模式列出仓库文件（支持 **）。"
                + "按修改时间倒序，最多返回 100 条。"
                + "不跟 symlink，跳过 node_modules/target/.git/dist。"
                + "只读工具，ask+code 均可用。",
                params(
                        Map.of("pattern", strProp("glob 模式，如 src/**/*.java 或 *.yml")),
                        List.of("pattern")));
    }

    private ToolDefinition buildMultiEditTool() {
        return tool("multiEditFile",
                "对同一文件依次按序应用多个 str_replace 编辑，任一失败则整体拒绝（无部分应用）。"
                + "全部成功后跑一次语法校验，最终插一条 FileChangeLog。"
                + "比多次调用 editFileContent 更原子，适合同一函数体多处改动。",
                params(
                        Map.of(
                                "filePath", strProp("目标文件相对路径"),
                                "edits", Map.of(
                                        "type", "array",
                                        "description", "编辑列表，按序应用",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "oldString", strProp("原字符串"),
                                                        "newString", strProp("新字符串")
                                                ),
                                                "required", List.of("oldString", "newString")
                                        ))
                        ),
                        List.of("filePath", "edits")));
    }

    private ToolDefinition buildPlanStructuredV2Tool() {
        return tool("planStructuredV2",
                "Submit a structured implementation plan after exploring the codebase with read-only tools. "
                + "Call this tool when you have gathered enough information to propose a concrete, "
                + "step-by-step plan. The plan will be shown to the user for approval before any changes are made.",
                params(
                        Map.of(
                                "title", strProp("Plan title (one sentence)"),
                                "approach", strProp("One-sentence summary of the overall approach"),
                                "steps", Map.of(
                                        "type", "array",
                                        "description", "Ordered implementation steps",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "title", strProp("Step title"),
                                                        "description", strProp("What this step does and why"),
                                                        "tools", Map.of(
                                                                "type", "array",
                                                                "items", Map.of("type", "string"),
                                                                "description", "Tools expected for this step"
                                                        ),
                                                        "filePath", strProp("Primary target file path, if applicable")
                                                ),
                                                "required", List.of("title", "description")
                                        ))
                        ),
                        List.of("title", "steps")));
    }

    private ToolDefinition buildTodoWriteTool() {
        return tool("TodoWrite",
                "覆盖式全量更新任务清单。每次调用替换整个清单（不是追加）。"
                + "规则：必须恰好有 1 个 in_progress 项；completed 只能单调递增不可回退。"
                + "完成一项后立刻调用 TodoWrite 把该项标 completed 并把下一项标 in_progress。",
                params(
                        Map.of(
                                "content", Map.of(
                                        "type", "array",
                                        "description", "完整任务清单，覆盖式更新",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "id", strProp("唯一标识"),
                                                        "content", strProp("任务描述"),
                                                        "status", strProp("pending | in_progress | completed"),
                                                        "activeForm", strProp("in_progress 时的进行中形式（可选）")
                                                ),
                                                "required", List.of("content", "status")
                                        ))
                        ),
                        List.of("content")));
    }

    private ToolDefinition buildTaskTool() {
        return tool("Task",
                "启动一个只读子代理来分析特定问题。子代理只用只读工具，不修改文件，"
                + "返回 1-2k 字摘要作为你的观察。适合让子代理搜索/分析某块代码，我继续做规划。",
                params(
                        Map.of(
                                "description", strProp("子代理任务简介（用于显示）"),
                                "prompt", strProp("发给子代理的完整提示词"),
                                "subagent_type", strProp("（可选）内置类型：general-purpose | explore | plan")
                        ),
                        List.of("description", "prompt")));
    }

    private ToolDefinition buildBashExecTool() {
        return tool("bashExec",
                "在仓库工作目录执行一个 bash 命令并返回输出。命令需通过安全检查（禁止 rm -rf/sudo/curl|sh 等）。超时 120 秒。",
                params(
                        Map.of(
                                "command", strProp("要执行的 bash 命令"),
                                "cwd", strProp("工作目录（可选，默认仓库根）")
                        ),
                        List.of("command")));
    }

    private ToolDefinition buildBashOutputTool() {
        return tool("bashOutput",
                "获取指定 shell 会话的当前输出（用于长时间运行的命令的增量输出）。",
                params(
                        Map.of("shellId", strProp("bashExec 返回的 shellId")),
                        List.of("shellId")));
    }

    private ToolDefinition buildKillBashTool() {
        return tool("killBash",
                "强制终止指定 shell 会话。",
                params(
                        Map.of("shellId", strProp("要终止的 shellId")),
                        List.of("shellId")));
    }

    private ToolDefinition buildRevisePlanTool() {
        return tool("revisePlan",
                "覆盖式重写当前执行计划（re-plan）。版本号 +1，SSE 推送 plan(kind:revised)。"
                + "高风险步骤（risk=D/E）需要用户再次审批后才执行。",
                params(
                        Map.of(
                                "reason", strProp("重新规划的原因"),
                                "newSteps", Map.of(
                                        "type", "array",
                                        "description", "新的执行步骤列表",
                                        "items", Map.of(
                                                "type", "object",
                                                "properties", Map.of(
                                                        "description", strProp("步骤描述"),
                                                        "sideEffects", strProp("副作用说明（可选）"),
                                                        "risk", strProp("风险级别 A/B/C/D/E（可选）")
                                                ),
                                                "required", List.of("description")
                                        ))
                        ),
                        List.of("reason", "newSteps")));
    }
}
