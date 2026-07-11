package com.repolens.service;

import com.repolens.domain.entity.CodeChunkEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.mapper.CodeChunkMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.impl.CodeChunkBuildServiceImpl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 FIX 3：chunk-size / chunk-overlap 生效，长文件被切成多个带重叠的 ~chunk-size 切片，
 * 短文件只产生一个 chunk，且行号元数据保持连续/重叠。
 */
@ExtendWith(MockitoExtension.class)
class CodeChunkBuildServiceImplTest {

    private static final int CHUNK_SIZE = 60;
    private static final int CHUNK_OVERLAP = 20;

    @Mock
    private RepoMapper repoMapper;
    @Mock
    private CodeFileMapper codeFileMapper;
    @Mock
    private CodeSymbolMapper codeSymbolMapper;
    @Mock
    private CodeChunkMapper codeChunkMapper;
    @Mock
    private PermissionService permissionService;
    @Mock
    private org.springframework.transaction.PlatformTransactionManager txManager;

    private CodeChunkBuildServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new CodeChunkBuildServiceImpl(
                repoMapper, codeFileMapper, codeSymbolMapper, codeChunkMapper, permissionService, txManager);
        ReflectionTestUtils.setField(service, "repoStorageRoot", tempDir.toString());
        ReflectionTestUtils.setField(service, "chunkSize", CHUNK_SIZE);
        ReflectionTestUtils.setField(service, "chunkOverlap", CHUNK_OVERLAP);
        ReflectionTestUtils.setField(service, "maxFileBytes", 500_000L);
    }

    @Test
    void longContent_shouldProduceMultipleOverlappingChunks() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            lines.add(String.format("line-%02d-payload", i)); // 每行约 15 字符
        }
        List<CodeChunkEntity> inserted = runBuild("notes.txt", lines);

        Assertions.assertTrue(inserted.size() > 1,
                "long content should be split into multiple chunks, got " + inserted.size());
        for (CodeChunkEntity chunk : inserted) {
            // 每个切片大致落在 chunk-size 附近，绝不应出现 8000 字符的硬切。
            Assertions.assertTrue(chunk.getContent().length() <= CHUNK_SIZE + 40,
                    "chunk should be near chunk-size, got " + chunk.getContent().length());
        }
        // 相邻切片按行号存在重叠：后一片的起始行不晚于前一片的结束行。
        boolean overlapFound = false;
        for (int i = 1; i < inserted.size(); i++) {
            int prevEnd = inserted.get(i - 1).getEndLine();
            int curStart = inserted.get(i).getStartLine();
            Assertions.assertTrue(curStart <= prevEnd + 1, "chunks must be contiguous or overlapping");
            if (curStart <= prevEnd) {
                overlapFound = true;
            }
        }
        Assertions.assertTrue(overlapFound, "consecutive chunks should overlap when overlap>0");
    }

    @Test
    void shortContent_shouldProduceSingleChunk() throws Exception {
        List<CodeChunkEntity> inserted = runBuild("small.txt", List.of("only one short line"));

        Assertions.assertEquals(1, inserted.size());
        Assertions.assertEquals(1, inserted.get(0).getStartLine());
        Assertions.assertEquals(1, inserted.get(0).getEndLine());
    }

    @Test
    void vendorMinifiedAndOversizedFiles_shouldBeSkipped_normalFileChunked() throws Exception {
        long repoId = 9L;
        long userId = 1L;

        RepoEntity repo = new RepoEntity();
        repo.setId(repoId);
        repo.setBranchName("main");
        Path repoDir = tempDir.resolve(String.valueOf(repoId)).resolve("main");
        Files.createDirectories(repoDir);

        // 正常源文件：应被切块。
        CodeFileEntity normal = fileEntity(101L, repoId, "src/notes.txt", "TEXT");
        Files.createDirectories(repoDir.resolve("src"));
        Files.write(repoDir.resolve("src").resolve("notes.txt"),
                "a normal source line".getBytes(StandardCharsets.UTF_8));

        // vendor / minified：路径过滤，无需落盘。
        CodeFileEntity minified = fileEntity(102L, repoId, "static/app.min.js", "JS");
        CodeFileEntity vendored = fileEntity(103L, repoId, "node_modules/element/element.js", "JS");

        // 超大文件：落盘且体积超过 maxFileBytes(此处调小到 50 字节)。
        ReflectionTestUtils.setField(service, "maxFileBytes", 50L);
        CodeFileEntity huge = fileEntity(104L, repoId, "big.txt", "TEXT");
        Files.write(repoDir.resolve("big.txt"), "x".repeat(500).getBytes(StandardCharsets.UTF_8));

        when(repoMapper.selectById(repoId)).thenReturn(repo);
        when(permissionService.checkRepoPermission(userId, repoId)).thenReturn(true);
        lenient().when(codeChunkMapper.delete(any())).thenReturn(0);
        when(codeFileMapper.selectList(any())).thenReturn(List.of(normal, minified, vendored, huge));
        when(codeSymbolMapper.selectList(any())).thenReturn(List.<CodeSymbolEntity>of());
        when(codeChunkMapper.insert(any(CodeChunkEntity.class))).thenReturn(1);

        service.buildChunks(repoId, userId);

        ArgumentCaptor<CodeChunkEntity> captor = ArgumentCaptor.forClass(CodeChunkEntity.class);
        verify(codeChunkMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        List<CodeChunkEntity> inserted = captor.getAllValues();

        // 只有正常源文件被切块；vendor / minified / 超大文件都被跳过。
        Assertions.assertTrue(inserted.stream().allMatch(c -> "src/notes.txt".equals(c.getFilePath())),
                "only the normal source file should be chunked, got "
                        + inserted.stream().map(CodeChunkEntity::getFilePath).toList());
        Assertions.assertFalse(inserted.isEmpty(), "the normal source file should produce at least one chunk");
    }

    private CodeFileEntity fileEntity(long id, long repoId, String path, String type) {
        CodeFileEntity file = new CodeFileEntity();
        file.setId(id);
        file.setRepoId(repoId);
        file.setFilePath(path);
        file.setFileType(type);
        return file;
    }

    private List<CodeChunkEntity> runBuild(String fileName, List<String> lines) throws Exception {
        long repoId = 9L;
        long userId = 1L;

        RepoEntity repo = new RepoEntity();
        repo.setId(repoId);
        repo.setBranchName("main");

        Path repoDir = tempDir.resolve(String.valueOf(repoId)).resolve("main");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve(fileName), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));

        CodeFileEntity file = new CodeFileEntity();
        file.setId(100L);
        file.setRepoId(repoId);
        file.setFilePath(fileName);
        file.setFileType("TEXT");

        when(repoMapper.selectById(repoId)).thenReturn(repo);
        when(permissionService.checkRepoPermission(userId, repoId)).thenReturn(true);
        lenient().when(codeChunkMapper.delete(any())).thenReturn(0);
        when(codeFileMapper.selectList(any())).thenReturn(List.of(file));
        when(codeSymbolMapper.selectList(any())).thenReturn(List.<CodeSymbolEntity>of());
        when(codeChunkMapper.insert(any(CodeChunkEntity.class))).thenReturn(1);

        service.buildChunks(repoId, userId);

        ArgumentCaptor<CodeChunkEntity> captor = ArgumentCaptor.forClass(CodeChunkEntity.class);
        verify(codeChunkMapper, org.mockito.Mockito.atLeastOnce()).insert(captor.capture());
        return captor.getAllValues();
    }
}
