package com.repolens.service.impl.support;

import com.repolens.llm.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全回归护栏：写工具（writeFileContent/editFileContent/createFileContent）和执行工具（runVerification）
 * 绝不能出现在 ask 模式工具集里，只能在 code 模式被注入。
 * 这条测试挂了 = 写/执行能力泄漏到了默认只读链路。
 */
class AgentToolCatalogTest {

    private final AgentToolCatalog catalog = new AgentToolCatalog(true, false);

    private Set<String> names(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::getName).collect(Collectors.toSet());
    }

    @Test
    void askMode_hasNoWriteTool() {
        assertThat(names(catalog.tools())).doesNotContain("writeFileContent");
        assertThat(names(catalog.tools(false))).doesNotContain("writeFileContent");
    }

    @Test
    void askMode_hasNoEditOrCreateOrExecTool() {
        Set<String> askNames = names(catalog.tools(false));
        assertThat(askNames).doesNotContain("editFileContent", "createFileContent", "runVerification");
        // 默认 tools() 也不含这些
        assertThat(names(catalog.tools())).doesNotContain("editFileContent", "createFileContent", "runVerification");
    }

    @Test
    void codeMode_hasWriteTool() {
        Set<String> codeNames = names(catalog.tools(true));
        assertThat(codeNames).contains("writeFileContent");
        // 只读工具仍在（写工具是叠加，不是替换）。
        assertThat(codeNames).contains("searchCodeChunks", "getFileContent");
        // code 模式至少比 ask 模式多写工具（数量随项目演进，不硬编码）
        assertThat(catalog.tools(true).size()).isGreaterThanOrEqualTo(catalog.tools(false).size() + 5);
    }

    @Test
    void codeMode_hasEditCreateAndVerifyTools() {
        Set<String> codeNames = names(catalog.tools(true));
        assertThat(codeNames).contains("editFileContent", "createFileContent", "runVerification");
    }

    @Test
    void writeToolNameSet_containsAllWriteTools() {
        assertThat(AgentToolCatalog.WRITE_TOOL_NAMES).contains(
                "writeFileContent", "editFileContent", "createFileContent", "deleteFile");
    }

    @Test
    void execToolNameSet_containsRunVerification() {
        assertThat(AgentToolCatalog.EXEC_TOOL_NAMES).contains("runVerification");
    }

    @Test
    void writeAndExecSetsAreDisjoint() {
        // 写工具和执行工具名单不重叠，便于测试和审计区分。
        Set<String> intersection = new java.util.HashSet<>(AgentToolCatalog.WRITE_TOOL_NAMES);
        intersection.retainAll(AgentToolCatalog.EXEC_TOOL_NAMES);
        assertThat(intersection).isEmpty();
    }

    @Test
    void webFetchEnabled_true_registersWebFetch() {
        AgentToolCatalog catalog = new AgentToolCatalog(true, false);
        Set<String> askNames = names(catalog.tools());
        assertThat(askNames).contains("webFetch");
        assertThat(askNames).contains("listDirectory");
    }

    @Test
    void webFetchEnabled_false_omitsWebFetch() {
        AgentToolCatalog catalog = new AgentToolCatalog(false, false);
        Set<String> askNames = names(catalog.tools());
        assertThat(askNames).doesNotContain("webFetch");
        assertThat(askNames).contains("listDirectory");
    }

    @Test
    void deleteFile_presentOnlyInCodeMode() {
        Set<String> codeNames = names(catalog.tools(true));
        Set<String> askNames = names(catalog.tools(false));
        assertThat(codeNames).contains("deleteFile");
        assertThat(askNames).doesNotContain("deleteFile");
    }

    @Test
    void writeToolNameSet_containsDeleteFile() {
        assertThat(AgentToolCatalog.WRITE_TOOL_NAMES).contains("deleteFile");
    }
}
