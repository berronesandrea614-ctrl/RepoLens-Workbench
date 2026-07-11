package com.repolens.service.impl;

import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.entity.ToolCallLogEntity;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.mapper.ToolCallLogMapper;
import com.repolens.mapper.VerificationRunMapper;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.DependencyCheckService;
import com.repolens.service.impl.support.CommandRunner;
import com.repolens.service.impl.support.WebFetcher;
import com.repolens.service.support.RepoWorkspaceResolver;
import com.repolens.service.support.ShadowWorkspaceManager;
import com.repolens.service.support.VerificationOutputParser;
import com.repolens.service.support.RipgrepRunner;
import com.repolens.service.support.SessionFileReadTracker;
import com.repolens.service.support.SyntaxValidator;
import com.repolens.service.support.FeatureLedgerService;
import org.springframework.test.util.ReflectionTestUtils;
import com.repolens.service.RepoFileWriteService;
import com.repolens.tool.ReadonlyToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写工具路由、暂存及 tool_call_log 审计验证：
 * 1. writeFileContent 现在<b>只暂存不写盘</b>——读旧全文、落一条 status=PROPOSED 的 file_change_log。
 * 2. editFileContent str_replace：唯一匹配替换、0次报错、多次报错。
 * 3. createFileContent：新文件暂存（oldContent 空）；已存在文件报错；symlink 父目录逃逸被拒。
 * 4. runVerification：kind 枚举校验；filter 注入被拒；命令数组构造正确。
 * 5. 所有工具调用（含写工具）都落一条 tool_call_log（含 sessionId 穿透）。
 */
class ToolInvokeServiceImplTest {

    @TempDir
    Path repoDir;

    private ReadonlyToolService readonlyToolService;
    private RepoFileWriteService repoFileWriteService;
    private FileChangeLogMapper fileChangeLogMapper;
    private RepoMapper repoMapper;
    private RepoWorkspaceResolver resolver;
    private ToolCallLogMapper toolCallLogMapper;
    private CommandRunner commandRunner;
    private WebFetcher webFetcher;
    private DependencyCheckService dependencyCheckService;
    private ChangeRiskService changeRiskService;
    private ToolInvokeServiceImpl service;

    @BeforeEach
    void setup() throws Exception {
        readonlyToolService = mock(ReadonlyToolService.class);
        repoFileWriteService = mock(RepoFileWriteService.class);
        fileChangeLogMapper = mock(FileChangeLogMapper.class);
        repoMapper = mock(RepoMapper.class);
        resolver = mock(RepoWorkspaceResolver.class);
        toolCallLogMapper = mock(ToolCallLogMapper.class);
        commandRunner = mock(CommandRunner.class);
        webFetcher = mock(WebFetcher.class);
        dependencyCheckService = mock(DependencyCheckService.class);
        changeRiskService = mock(ChangeRiskService.class);
        ShadowWorkspaceManager shadowManager = mock(ShadowWorkspaceManager.class);
        // 影子区默认无 ACTIVE（不影响现有测试）
        when(shadowManager.resolveActive(any(), any())).thenReturn(java.util.Optional.empty());
        when(shadowManager.resolveOrCreate(any(), any(), any())).thenReturn(null);

        VerificationOutputParser verificationOutputParser = mock(VerificationOutputParser.class);
        when(verificationOutputParser.parse(any(), any())).thenReturn(List.of());

        RipgrepRunner ripgrepRunner = mock(RipgrepRunner.class);
        SessionFileReadTracker sessionFileReadTracker = mock(SessionFileReadTracker.class);
        SyntaxValidator syntaxValidator = mock(SyntaxValidator.class);
        VerificationRunMapper verificationRunMapper = mock(VerificationRunMapper.class);
        FeatureLedgerService featureLedgerService = mock(FeatureLedgerService.class);

        RepoEntity repo = new RepoEntity();
        repo.setId(5L);
        repo.setBranchName("main");
        when(repoMapper.selectById(eq(5L))).thenReturn(repo);
        when(resolver.resolveRepoDirectory(any())).thenReturn(repoDir);
        when(resolver.resolveSafeFilePath(any(), any())).thenAnswer(inv ->
                ((Path) inv.getArgument(0)).resolve((String) inv.getArgument(1)).normalize());
        // resolveSafeNewFilePath: allow within repoDir
        when(resolver.resolveSafeNewFilePath(any(), any())).thenAnswer(inv ->
                ((Path) inv.getArgument(0)).resolve((String) inv.getArgument(1)).normalize());

        Files.createDirectories(repoDir.resolve("src"));
        Files.writeString(repoDir.resolve("src/A.java"), "class A { void foo() {} }");

        // 让 insert 回填自增 id，模拟 DB 行为。
        when(fileChangeLogMapper.insert(any(FileChangeLogEntity.class))).thenAnswer(inv -> {
            ((FileChangeLogEntity) inv.getArgument(0)).setId(77L);
            return 1;
        });

        service = new ToolInvokeServiceImpl(
                readonlyToolService, repoFileWriteService, fileChangeLogMapper, repoMapper, resolver,
                toolCallLogMapper, commandRunner, webFetcher, dependencyCheckService, changeRiskService,
                shadowManager, verificationOutputParser, ripgrepRunner, sessionFileReadTracker,
                syntaxValidator, verificationRunMapper, featureLedgerService,
                mock(com.repolens.service.support.PersistentShell.class));
        ReflectionTestUtils.setField(service, "webFetchEnabled", true);
        ReflectionTestUtils.setField(service, "requireReadFirst", false);
        ReflectionTestUtils.setField(service, "syntaxGuardEnabled", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writeFileContent_stagesProposedChangeWithoutWritingDisk() throws Exception {
        Object result = service.invoke(1L, 5L, 202L, "writeFileContent",
                Map.of("filePath", "src/A.java", "content", "NEW_CONTENT"));

        // 关键：绝不写盘——既不调用写服务，磁盘文件内容也保持不变。
        verify(repoFileWriteService, never()).writeFile(any(), any(FileWriteRequest.class));
        assertThat(Files.readString(repoDir.resolve("src/A.java"))).isEqualTo("class A { void foo() {} }");

        // 落一条 PROPOSED 变更：old 是当前全文，new 是提议内容，sessionId 透传，未回滚。
        ArgumentCaptor<FileChangeLogEntity> logCaptor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(logCaptor.capture());
        FileChangeLogEntity logged = logCaptor.getValue();
        assertThat(logged.getRepoId()).isEqualTo(5L);
        assertThat(logged.getSessionId()).isEqualTo(202L);
        assertThat(logged.getFilePath()).isEqualTo("src/A.java");
        assertThat(logged.getOldContent()).isEqualTo("class A { void foo() {} }");
        assertThat(logged.getNewContent()).isEqualTo("NEW_CONTENT");
        assertThat(logged.getReverted()).isEqualTo(0);
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);

        // 返回精简结果 {filePath, changeId, staged:true}，changeId = 落库自增 id。
        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("filePath")).isEqualTo("src/A.java");
        assertThat(map.get("changeId")).isEqualTo(77L);
        assertThat(map.get("staged")).isEqualTo(true);
    }

    // ——————————— editFileContent ———————————

    @Test
    @SuppressWarnings("unchecked")
    void editFileContent_uniqueMatch_stagesReplacedContent() throws Exception {
        Object result = service.invoke(1L, 5L, 202L, "editFileContent",
                Map.of("filePath", "src/A.java",
                        "oldString", "void foo() {}",
                        "newString", "void bar() {}"));

        verify(repoFileWriteService, never()).writeFile(any(), any());
        // 磁盘文件不变
        assertThat(Files.readString(repoDir.resolve("src/A.java"))).isEqualTo("class A { void foo() {} }");

        ArgumentCaptor<FileChangeLogEntity> captor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(captor.capture());
        FileChangeLogEntity logged = captor.getValue();
        assertThat(logged.getOldContent()).isEqualTo("class A { void foo() {} }");
        assertThat(logged.getNewContent()).isEqualTo("class A { void bar() {} }");
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("staged")).isEqualTo(true);
    }

    @Test
    void editFileContent_noMatch_throwsBizException() {
        assertThatThrownBy(() -> service.invoke(1L, 5L, 202L, "editFileContent",
                Map.of("filePath", "src/A.java",
                        "oldString", "THIS_DOES_NOT_EXIST",
                        "newString", "replacement")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未找到");
    }

    @Test
    void editFileContent_multipleMatch_throwsBizException() throws Exception {
        // 写一个含两处相同子串的文件
        Files.writeString(repoDir.resolve("src/A.java"), "foo foo");
        assertThatThrownBy(() -> service.invoke(1L, 5L, 202L, "editFileContent",
                Map.of("filePath", "src/A.java",
                        "oldString", "foo",
                        "newString", "bar")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不唯一");
    }

    // ——————————— createFileContent ———————————

    @Test
    @SuppressWarnings("unchecked")
    void createFileContent_newFile_stagesWithEmptyOldContent() {
        Object result = service.invoke(1L, 5L, 303L, "createFileContent",
                Map.of("filePath", "src/NewClass.java",
                        "content", "class NewClass {}"));

        ArgumentCaptor<FileChangeLogEntity> captor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(captor.capture());
        FileChangeLogEntity logged = captor.getValue();
        assertThat(logged.getOldContent()).isEmpty();   // 新建标志：oldContent 为空
        assertThat(logged.getNewContent()).isEqualTo("class NewClass {}");
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);
        assertThat(logged.getSessionId()).isEqualTo(303L);

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("staged")).isEqualTo(true);
        // 磁盘尚未创建任何新文件
        assertThat(repoDir.resolve("src/NewClass.java").toFile()).doesNotExist();
    }

    @Test
    void createFileContent_existingFile_throwsBizException() {
        // src/A.java 已存在
        assertThatThrownBy(() -> service.invoke(1L, 5L, 303L, "createFileContent",
                Map.of("filePath", "src/A.java",
                        "content", "whatever")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createFileContent_symlinkParentEscape_throwsBizException() throws Exception {
        // 模拟 resolveSafeNewFilePath 抛出 BizException（symlink 逃逸被拒）。
        // 使用 doThrow().when() 而非 when().thenThrow()，避免触发 setup 阶段已有的 thenAnswer。
        org.mockito.Mockito.doThrow(new com.repolens.common.exception.BizException(
                        com.repolens.common.constants.ErrorCode.BAD_REQUEST,
                        "File path escapes repository root"))
                .when(resolver).resolveSafeNewFilePath(any(), any());

        assertThatThrownBy(() -> service.invoke(1L, 5L, 303L, "createFileContent",
                Map.of("filePath", "../outside/evil.java",
                        "content", "malicious")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("escapes");
    }

    // ——————————— runVerification ———————————

    @Test
    @SuppressWarnings("unchecked")
    void runVerification_build_constructsCorrectMavenCommand() throws Exception {
        Files.writeString(repoDir.resolve("pom.xml"), "<project/>");
        when(commandRunner.run(any(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS)))
                .thenReturn(new CommandRunner.RunResult(0, false, "BUILD SUCCESS"));

        Object result = service.invoke(1L, 5L, null, "runVerification",
                Map.of("kind", "build"));

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        verify(commandRunner).run(cmdCaptor.capture(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS));
        String[] cmd = cmdCaptor.getValue();
        assertThat(cmd).containsExactly("mvn", "-q", "compile");

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("exitCode")).isEqualTo(0);
        assertThat(map.get("timedOut")).isEqualTo(false);
        assertThat(map.get("outputTail")).isEqualTo("BUILD SUCCESS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void runVerification_testWithFilter_constructsMavenTestCommand() throws Exception {
        Files.writeString(repoDir.resolve("pom.xml"), "<project/>");
        when(commandRunner.run(any(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS)))
                .thenReturn(new CommandRunner.RunResult(0, false, "Tests passed"));

        service.invoke(1L, 5L, null, "runVerification",
                Map.of("kind", "test", "testFilter", "MyServiceTest#myMethod"));

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        verify(commandRunner).run(cmdCaptor.capture(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS));
        assertThat(cmdCaptor.getValue()).containsExactly("mvn", "-q", "test",
                "-Dtest=MyServiceTest#myMethod");
    }

    @Test
    void runVerification_invalidKind_throwsBizException() {
        assertThatThrownBy(() -> service.invoke(1L, 5L, null, "runVerification",
                Map.of("kind", "deploy")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void runVerification_filterWithInjection_throwsBizException() throws Exception {
        Files.writeString(repoDir.resolve("pom.xml"), "<project/>");
        // 注入尝试：分号、美元符、反引号均不在白名单 [A-Za-z0-9_.*#,]+
        for (String badFilter : new String[]{"foo; rm -rf /", "$(cmd)", "`cmd`", "../etc"}) {
            assertThatThrownBy(() -> service.invoke(1L, 5L, null, "runVerification",
                    Map.of("kind", "test", "testFilter", badFilter)))
                    .as("filter should be rejected: " + badFilter)
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("不安全");
        }
    }

    @Test
    void runVerification_npmProject_constructsNpmCommand() throws Exception {
        Files.writeString(repoDir.resolve("package.json"), "{}");
        when(commandRunner.run(any(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS)))
                .thenReturn(new CommandRunner.RunResult(0, false, "ok"));

        service.invoke(1L, 5L, null, "runVerification", Map.of("kind", "build"));

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        verify(commandRunner).run(cmdCaptor.capture(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS));
        assertThat(cmdCaptor.getValue()).containsExactly("npm", "run", "build", "--silent");
    }

    @Test
    void runVerification_unknownProjectType_throwsBizException() {
        // 不创建 pom.xml 或 package.json
        assertThatThrownBy(() -> service.invoke(1L, 5L, null, "runVerification",
                Map.of("kind", "build")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void writeFileContent_logsToolCallLogWithSessionId() {
        service.invoke(1L, 5L, 202L, "writeFileContent",
                Map.of("filePath", "src/A.java", "content", "NEW_CONTENT"));

        // tool_call_log should be inserted with the correct sessionId, toolName, success flag.
        ArgumentCaptor<ToolCallLogEntity> captor = ArgumentCaptor.forClass(ToolCallLogEntity.class);
        verify(toolCallLogMapper).insert(captor.capture());

        ToolCallLogEntity entry = captor.getValue();
        assertThat(entry.getToolName()).isEqualTo("writeFileContent");
        assertThat(entry.getSessionId()).isEqualTo(202L);
        assertThat(entry.getRepoId()).isEqualTo(5L);
        assertThat(entry.getUserId()).isEqualTo(1L);
        assertThat(entry.getSuccess()).isTrue();
        assertThat(entry.getErrorMsg()).isNull();
        // outputJson should contain the staged result (filePath, changeId, staged).
        assertThat(entry.getOutputJson()).contains("staged");
    }

    @Test
    void readTool_logsToolCallLogWithSessionId() {
        // searchCodeChunks is a read tool; it should also produce a tool_call_log.
        when(readonlyToolService.searchCodeChunks(eq(1L), eq(5L), eq("user"), any()))
                .thenReturn(java.util.List.of());

        service.invoke(1L, 5L, 303L, "searchCodeChunks", Map.of("query", "user", "topK", 5));

        ArgumentCaptor<ToolCallLogEntity> captor = ArgumentCaptor.forClass(ToolCallLogEntity.class);
        verify(toolCallLogMapper).insert(captor.capture());

        ToolCallLogEntity entry = captor.getValue();
        assertThat(entry.getToolName()).isEqualTo("searchCodeChunks");
        assertThat(entry.getSessionId()).isEqualTo(303L);
        assertThat(entry.getSuccess()).isTrue();
        // inputJson should contain the query arg.
        assertThat(entry.getInputJson()).contains("user");
    }

    // ——————————— webFetch ———————————

    @Test
    @SuppressWarnings("unchecked")
    void webFetch_normalUrl_returnsFetcherResult() {
        when(webFetcher.fetch(eq("https://example.com"), eq(20000)))
                .thenReturn(new WebFetcher.FetchResult("https://example.com", 200, "text/html", false, "Hello world"));

        Object result = service.invoke(1L, 5L, null, "webFetch", Map.of("url", "https://example.com"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("status")).isEqualTo(200);
        assertThat(map.get("content")).isEqualTo("Hello world");
        assertThat(map.get("truncated")).isEqualTo(false);
        verify(webFetcher).fetch(eq("https://example.com"), eq(20000));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetch_ssrfLoopback_returnsMinus1WithoutCallingFetcher() {
        Object result = service.invoke(1L, 5L, null, "webFetch", Map.of("url", "http://127.0.0.1/x"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("status")).isEqualTo(-1);
        assertThat((String) map.get("content")).contains("抓取失败");
        verify(webFetcher, never()).fetch(any(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetch_ssrfPrivateNetwork_returnsMinus1WithoutCallingFetcher() {
        Object result = service.invoke(1L, 5L, null, "webFetch", Map.of("url", "http://192.168.0.1/x"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("status")).isEqualTo(-1);
        verify(webFetcher, never()).fetch(any(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetch_nonHttpScheme_returnsMinus1WithoutCallingFetcher() {
        Object result = service.invoke(1L, 5L, null, "webFetch", Map.of("url", "ftp://example.com/file"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("status")).isEqualTo(-1);
        assertThat((String) map.get("content")).contains("仅支持");
        verify(webFetcher, never()).fetch(any(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void webFetch_disabled_returnsDisabledMessage() {
        ReflectionTestUtils.setField(service, "webFetchEnabled", false);

        Object result = service.invoke(1L, 5L, null, "webFetch", Map.of("url", "https://example.com"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("status")).isEqualTo(-1);
        assertThat((String) map.get("content")).contains("禁用");
        verify(webFetcher, never()).fetch(any(), anyInt());

        // restore
        ReflectionTestUtils.setField(service, "webFetchEnabled", true);
    }

    // ——————————— listDirectory ———————————

    @Test
    @SuppressWarnings("unchecked")
    void listDirectory_listsFilesAndDirs() {
        // repoDir already has src/ directory with A.java inside (created in setup)
        Object result = service.invoke(1L, 5L, null, "listDirectory", Map.of("path", "src"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("truncated")).isEqualTo(false);
        java.util.List<Map<String, Object>> items = (java.util.List<Map<String, Object>>) map.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("name")).isEqualTo("A.java");
        assertThat(items.get(0).get("type")).isEqualTo("file");
        assertThat(items.get(0)).containsKey("size");
    }

    @Test
    void listDirectory_pathTraversal_throwsBizException() {
        // Mock resolveSafeFilePath to throw BizException for path traversal
        org.mockito.Mockito.doThrow(new com.repolens.common.exception.BizException(
                        com.repolens.common.constants.ErrorCode.BAD_REQUEST,
                        "File path escapes repository root"))
                .when(resolver).resolveSafeFilePath(any(), eq("../x"));

        assertThatThrownBy(() -> service.invoke(1L, 5L, null, "listDirectory", Map.of("path", "../x")))
                .isInstanceOf(com.repolens.common.exception.BizException.class)
                .hasMessageContaining("escapes");
    }

    // ——————————— deleteFile ———————————

    @Test
    @SuppressWarnings("unchecked")
    void deleteFile_stagesProposedDeleteWithoutDiskDeletion() throws Exception {
        Object result = service.invoke(1L, 5L, 404L, "deleteFile", Map.of("filePath", "src/A.java"));

        // File must not be deleted from disk
        assertThat(repoDir.resolve("src/A.java").toFile()).exists();

        ArgumentCaptor<FileChangeLogEntity> captor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(captor.capture());
        FileChangeLogEntity logged = captor.getValue();
        assertThat(logged.getRepoId()).isEqualTo(5L);
        assertThat(logged.getSessionId()).isEqualTo(404L);
        assertThat(logged.getFilePath()).isEqualTo("src/A.java");
        assertThat(logged.getOldContent()).isEqualTo("class A { void foo() {} }");
        assertThat(logged.getNewContent()).isNull();
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);
        assertThat(logged.getOpType()).isEqualTo(FileChangeLogEntity.OP_TYPE_DELETE);

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("staged")).isEqualTo(true);
        assertThat(map.get("filePath")).isEqualTo("src/A.java");
    }

    @Test
    void deleteFile_nonExistentFile_throwsBizException() {
        assertThatThrownBy(() -> service.invoke(1L, 5L, null, "deleteFile",
                Map.of("filePath", "src/DoesNotExist.java")))
                .isInstanceOf(com.repolens.common.exception.BizException.class);
    }

    @Test
    void deleteFile_inWriteToolNames() {
        assertThat(com.repolens.service.impl.support.AgentToolCatalog.WRITE_TOOL_NAMES)
                .contains("deleteFile");
    }

    // ——————————— runVerification (new: nested pom, no build files, no -o) ———————————

    @Test
    @SuppressWarnings("unchecked")
    void runVerification_nestedPomXml_usesBuildSubdir() throws Exception {
        // Create sub/pom.xml (no pom at root)
        java.nio.file.Files.createDirectories(repoDir.resolve("sub"));
        java.nio.file.Files.writeString(repoDir.resolve("sub/pom.xml"), "<project/>");
        when(commandRunner.run(any(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS)))
                .thenReturn(new CommandRunner.RunResult(0, false, "BUILD SUCCESS"));

        Object result = service.invoke(1L, 5L, null, "runVerification", Map.of("kind", "build"));

        // Capture the workDir passed to commandRunner
        ArgumentCaptor<java.nio.file.Path> cwdCaptor = ArgumentCaptor.forClass(java.nio.file.Path.class);
        verify(commandRunner).run(any(), cwdCaptor.capture(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS));
        assertThat(cwdCaptor.getValue()).isEqualTo(repoDir.resolve("sub"));

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("buildDir")).isEqualTo("sub");
    }

    @Test
    void runVerification_noBuildFiles_throwsBizException() {
        // No pom.xml or package.json anywhere (setup only creates src/A.java)
        assertThatThrownBy(() -> service.invoke(1L, 5L, null, "runVerification", Map.of("kind", "build")))
                .isInstanceOf(com.repolens.common.exception.BizException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    void runVerification_build_commandDoesNotContainOfflineFlag() throws Exception {
        java.nio.file.Files.writeString(repoDir.resolve("pom.xml"), "<project/>");
        when(commandRunner.run(any(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS)))
                .thenReturn(new CommandRunner.RunResult(0, false, "ok"));

        service.invoke(1L, 5L, null, "runVerification", Map.of("kind", "build"));

        ArgumentCaptor<String[]> cmdCaptor = ArgumentCaptor.forClass(String[].class);
        verify(commandRunner).run(cmdCaptor.capture(), any(), eq(ToolInvokeServiceImpl.VERIFY_TIMEOUT_MS));
        assertThat(cmdCaptor.getValue()).doesNotContain("-o");
    }

    // ——————————— K P1: branchId 隔离链路 ———————————

    @Test
    @SuppressWarnings("unchecked")
    void writeFileContent_withBranchId_persistsBranchIdInChangeLog() throws Exception {
        // 调用新的 7-param invoke，携带 branchId="v1"
        Object result = service.invoke(1L, 5L, 202L, "writeFileContent",
                Map.of("filePath", "src/A.java", "content", "BRANCH_CONTENT"), 99L, "v1");

        ArgumentCaptor<FileChangeLogEntity> captor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(captor.capture());
        FileChangeLogEntity logged = captor.getValue();

        // branchId 必须落库
        assertThat(logged.getBranchId()).isEqualTo("v1");
        // 其他字段不变
        assertThat(logged.getRepoId()).isEqualTo(5L);
        assertThat(logged.getSessionId()).isEqualTo(202L);
        assertThat(logged.getFilePath()).isEqualTo("src/A.java");
        assertThat(logged.getNewContent()).isEqualTo("BRANCH_CONTENT");
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);

        Map<String, Object> map = (Map<String, Object>) result;
        assertThat(map.get("staged")).isEqualTo(true);
    }

    @Test
    void writeFileContent_oldSignatureInvoke_branchIdIsNull() throws Exception {
        // 旧 6-param 签名（无 branchId）→ branchId 落库必须为 null（现有行为不变）
        service.invoke(1L, 5L, 202L, "writeFileContent",
                Map.of("filePath", "src/A.java", "content", "OLD_CONTENT"), null);

        ArgumentCaptor<FileChangeLogEntity> captor = ArgumentCaptor.forClass(FileChangeLogEntity.class);
        verify(fileChangeLogMapper).insert(captor.capture());
        FileChangeLogEntity logged = captor.getValue();

        assertThat(logged.getBranchId()).isNull();
        assertThat(logged.getStatus()).isEqualTo(FileChangeLogEntity.STATUS_PROPOSED);
    }
}
