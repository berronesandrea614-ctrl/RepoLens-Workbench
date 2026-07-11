package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.CodeDependencyEntity;
import com.repolens.domain.entity.CodeFileEntity;
import com.repolens.domain.entity.CodeSymbolEntity;
import com.repolens.kernel.drift.spi.CallGraphSnapshotProvider;
import com.repolens.kernel.drift.spi.CallGraphView;
import com.repolens.kernel.drift.spi.DependencyEdge;
import com.repolens.kernel.drift.spi.FileFingerprint;
import com.repolens.kernel.drift.spi.SymbolNode;
import com.repolens.mapper.CodeDependencyMapper;
import com.repolens.mapper.CodeFileMapper;
import com.repolens.mapper.CodeSymbolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 内核 M9「架构漂移时间维度」的<b>只读适配</b>：把本模块当前态的调用图
 * （code_symbol / code_dependency / code_file）映射成内核侧 SPI DTO，供
 * {@link CallGraphSnapshotProvider} 消费。
 *
 * <p><b>边界</b>：内核只依赖 {@code com.repolens.kernel.drift.spi} 端口 + 其 DTO，
 * 不直接引用本模块的表 / Graph·Symbol VO / graphApi·symbolApi；本类是那唯一的适配点。
 * 只读、无副作用、不改任何 schema。
 *
 * <p><b>无历史维度</b>：调用图每次(重)索引全量覆盖、只留当前一份，故本 provider 返回的
 * 始终是「当前整张图」。跨快照的历史/回放/漂移由内核侧用 rk_ 表自行捕获（用 SymbolNode
 * 的语义 stableKey 作跨快照身份，不依赖会变的自增 id）。
 */
@Service("callGraphSnapshotProvider")
@RequiredArgsConstructor
public class CallGraphSnapshotProviderImpl implements CallGraphSnapshotProvider {

    private final CodeSymbolMapper codeSymbolMapper;
    private final CodeDependencyMapper codeDependencyMapper;
    private final CodeFileMapper codeFileMapper;

    @Override
    public CallGraphView currentGraph(long repoId) {
        List<CodeFileEntity> files = codeFileMapper.selectList(
                Wrappers.<CodeFileEntity>lambdaQuery().eq(CodeFileEntity::getRepoId, repoId));
        List<CodeSymbolEntity> symbols = codeSymbolMapper.selectList(
                Wrappers.<CodeSymbolEntity>lambdaQuery().eq(CodeSymbolEntity::getRepoId, repoId));
        List<CodeDependencyEntity> deps = codeDependencyMapper.selectList(
                Wrappers.<CodeDependencyEntity>lambdaQuery().eq(CodeDependencyEntity::getRepoId, repoId));

        if (files.isEmpty() && symbols.isEmpty() && deps.isEmpty()) {
            return CallGraphView.empty(repoId);
        }

        // code_symbol 只有 file_id，join code_file 取 file_path（契约要求 SymbolNode.filePath）
        Map<Long, String> filePathById = files.stream()
                .collect(Collectors.toMap(CodeFileEntity::getId, CodeFileEntity::getFilePath, (a, b) -> a));

        List<SymbolNode> nodes = symbols.stream()
                .map(s -> new SymbolNode(
                        s.getId(),
                        s.getLanguage(),
                        s.getSymbolType() == null ? null : s.getSymbolType().name(),
                        s.getClassName(),
                        s.getMethodName(),
                        s.getSignature(),
                        filePathById.get(s.getFileId()),
                        nz(s.getStartLine()),
                        nz(s.getEndLine())))
                .collect(Collectors.toList());

        List<DependencyEdge> edges = deps.stream()
                .map(d -> new DependencyEdge(
                        d.getSourceSymbolId(),
                        d.getTargetSymbolName(),
                        d.getRelationType(),
                        d.getConfidence() == null ? 1.0 : d.getConfidence().doubleValue()))
                .collect(Collectors.toList());

        List<FileFingerprint> fingerprints = files.stream()
                .map((Function<CodeFileEntity, FileFingerprint>) f -> new FileFingerprint(
                        f.getFilePath(),
                        f.getContentHash(),
                        f.getLastCommitId(),
                        nz(f.getLineCount())))
                .collect(Collectors.toList());

        return new CallGraphView(repoId, nodes, edges, fingerprints);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
