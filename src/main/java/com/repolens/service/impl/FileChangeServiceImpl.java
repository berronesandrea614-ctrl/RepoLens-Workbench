package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.FileWriteRequest;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import com.repolens.domain.entity.FileChangeLogEntity;
import com.repolens.domain.entity.RepoEntity;
import com.repolens.domain.enums.RepoIndexStatus;
import com.repolens.domain.vo.FileChangeDetailVO;
import com.repolens.domain.vo.FileChangeVO;
import com.repolens.mapper.FileChangeLogMapper;
import com.repolens.mapper.RepoMapper;
import com.repolens.security.PermissionService;
import com.repolens.service.ChangeRiskService;
import com.repolens.service.ComprehensionDebtService;
import com.repolens.service.FileChangeService;
import com.repolens.service.ProvenanceService;
import com.repolens.service.RepoFileWriteService;
import com.repolens.service.TraceabilityService;
import com.repolens.service.support.ShadowWorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 文件变更查看 + 审批（apply/reject）+ 回滚实现。
 * 审批门：writeFileContent 只暂存 PROPOSED；apply 才经 RepoFileWriteService 写盘（天然带
 * 路径安全 + 大小上限 + 审计，绝不新建文件），reject 只改状态、不写盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileChangeServiceImpl implements FileChangeService {

    private final PermissionService permissionService;
    private final FileChangeLogMapper fileChangeLogMapper;
    private final RepoFileWriteService repoFileWriteService;
    private final RepoMapper repoMapper;
    private final ComprehensionDebtService comprehensionDebtService;
    private final ProvenanceService provenanceService;
    private final TraceabilityService traceabilityService;
    private final ChangeRiskService changeRiskService;
    private final ShadowWorkspaceManager shadowManager;

    @Override
    public List<FileChangeDetailVO> listChanges(Long userId, Long repoId, Long sessionId) {
        ensureRepoPermission(userId, repoId);
        List<FileChangeLogEntity> changes = fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(sessionId != null, FileChangeLogEntity::getSessionId, sessionId)
                        .orderByDesc(FileChangeLogEntity::getId));
        return changes.stream()
                .map(c -> FileChangeDetailVO.builder()
                        .id(c.getId())
                        .filePath(c.getFilePath())
                        .oldContent(c.getOldContent())
                        .newContent(c.getNewContent())
                        .createdAt(c.getCreatedAt())
                        .reverted(c.getReverted())
                        .status(c.getStatus())
                        .build())
                .toList();
    }

    @Override
    public FileChangeVO apply(Long userId, Long repoId, Long changeId, boolean ack) {
        ensureRepoPermission(userId, repoId);
        FileChangeLogEntity target = loadProposed(repoId, changeId);
        List<Long> dechained = applyOne(userId, repoId, target, ack);
        return summary(target, dechained);
    }

    @Override
    public FileChangeVO reject(Long userId, Long repoId, Long changeId) {
        ensureRepoPermission(userId, repoId);
        FileChangeLogEntity target = loadProposed(repoId, changeId);
        rejectOne(userId, repoId, target);
        return summary(target, List.of());
    }

    @Override
    public List<FileChangeVO> applyAll(Long userId, Long repoId, Long sessionId, boolean ack) {
        return applyAll(userId, repoId, sessionId, (String) null, ack);
    }

    @Override
    public List<FileChangeVO> applyAll(Long userId, Long repoId, Long sessionId, String branchId, boolean ack) {
        ensureRepoPermission(userId, repoId);
        List<FileChangeLogEntity> proposed = listProposed(repoId, sessionId, branchId);
        List<FileChangeVO> results = new ArrayList<>();
        for (FileChangeLogEntity c : proposed) {
            List<Long> dechained = applyOne(userId, repoId, c, ack);
            results.add(summary(c, dechained));
        }
        try {
            shadowManager.merge(repoId, sessionId);
        } catch (Exception e) {
            log.warn("shadow merge failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
        }
        return results;
    }

    @Override
    public List<FileChangeVO> rejectAll(Long userId, Long repoId, Long sessionId) {
        ensureRepoPermission(userId, repoId);
        List<FileChangeLogEntity> proposed = listProposed(repoId, sessionId, null);
        for (FileChangeLogEntity c : proposed) {
            rejectOne(userId, repoId, c);
        }
        try {
            shadowManager.discard(repoId, sessionId);
        } catch (Exception e) {
            log.warn("shadow discard failed repoId={} sessionId={}: {}", repoId, sessionId, e.getMessage());
        }
        return proposed.stream().map(c -> summary(c, List.of())).toList();
    }

    /**
     * 写盘一条 PROPOSED 变更并置 APPLIED（这是"审批 → 写盘"的门）。
     * 路由规则（优先 opType，其次 oldContent 空启发向下兼容）：
     * <ul>
     *   <li>opType=DELETE → 永久删除文件；</li>
     *   <li>opType=CREATE → createFile（建新文件）；</li>
     *   <li>opType=WRITE → writeFile（覆盖现有文件）；</li>
     *   <li>opType=NULL（旧行）→ oldContent 空则 createFile，否则 writeFile。</li>
     * </ul>
     * 写盘成功后把 repo 索引状态标记为 STALE，驱动前端「索引已过期」徽标亮起。
     */
    /**
     * 写盘一条 PROPOSED 变更并置 APPLIED。返回本次 apply 导致 trace link STALE/BROKEN 的需求 ID 列表。
     */
    private List<Long> applyOne(Long userId, Long repoId, FileChangeLogEntity target, boolean ack) {
        // ——— BLOCK 门（同步兜底防竞态）———
        if (!ack) {
            List<ChangeRiskFlagEntity> blockers = changeRiskService.getUnacknowledgedBlockers(target.getId());
            if (blockers.isEmpty() && !changeRiskService.hasFlags(target.getId())) {
                // 表无任何记录 → 异步扫描尚未落库（真竞态）→ 同步兜底
                blockers = changeRiskService.detectSync(repoId, target.getId()).stream()
                        .filter(f -> "BLOCK".equals(f.getSeverity())).toList();
            }
            // 若表里已有记录但 blockers 为空，说明全部已 acknowledge → 放行
            if (!blockers.isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "存在未确认的破坏性风险(" + blockers.size() + "),请勾选确认后再应用");
            }
        }

        String opType = target.getOpType();

        if (FileChangeLogEntity.OP_TYPE_DELETE.equals(opType)) {
            // deleteFile 产生的 PROPOSED 变更：永久删除文件（已通过 staged 审批门）。
            repoFileWriteService.deleteFile(userId, repoId, target.getFilePath());
            target.setStatus(FileChangeLogEntity.STATUS_APPLIED);
            target.setApprovedBy(userId);
            target.setApprovedAt(java.time.LocalDateTime.now());
            fileChangeLogMapper.updateById(target);
            log.info("AUDIT file-change-apply user={} repo={} changeId={} path={} opType=DELETE",
                    userId, repoId, target.getId(), target.getFilePath());
            // F: append provenance ledger record (failure-safe)
            provenanceService.appendRecordSafe(repoId, target.getId(), target.getFilePath(),
                    target.getOldContent(), target.getNewContent(),
                    target.getLlmCallId(), target.getAgentRunId(),
                    "APPROVED", userId);
            markIndexStaleSafe(repoId);
            comprehensionDebtService.markDebtStale(repoId, target.getFilePath());
            // C P1: mark trace links BROKEN for deleted file
            return markDechainSafe(repoId, target.getFilePath(), true);
        }

        String content = target.getNewContent() == null ? "" : target.getNewContent();
        FileWriteRequest writeRequest = new FileWriteRequest();
        writeRequest.setRepoId(repoId);
        writeRequest.setFilePath(target.getFilePath());
        writeRequest.setContent(content);

        // 区分"新建文件"与"修改现有文件"：优先使用 opType（显式标记）；
        // 旧行（opType=NULL）降级走 oldContent 空启发（向下兼容）。
        boolean isCreate;
        if (FileChangeLogEntity.OP_TYPE_CREATE.equals(opType)) {
            isCreate = true;
        } else if (FileChangeLogEntity.OP_TYPE_WRITE.equals(opType)) {
            isCreate = false;
        } else {
            // 旧数据（opType 为 null）：保留原启发，以 oldContent 是否为空判断。
            isCreate = target.getOldContent() == null || target.getOldContent().isEmpty();
        }
        if (isCreate) {
            // createFileContent 产生的 PROPOSED 变更：需创建新文件（含父目录）。
            repoFileWriteService.createFile(userId, writeRequest);
        } else {
            // writeFileContent / editFileContent 产生的 PROPOSED 变更：覆盖已存在文件。
            repoFileWriteService.writeFile(userId, writeRequest);
        }

        target.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        target.setApprovedBy(userId);
        target.setApprovedAt(java.time.LocalDateTime.now());
        fileChangeLogMapper.updateById(target);
        log.info("AUDIT file-change-apply user={} repo={} changeId={} path={} isCreate={}",
                userId, repoId, target.getId(), target.getFilePath(), isCreate);
        // F: append provenance ledger record (failure-safe)
        provenanceService.appendRecordSafe(repoId, target.getId(), target.getFilePath(),
                target.getOldContent(), target.getNewContent(),
                target.getLlmCallId(), target.getAgentRunId(),
                "APPROVED", userId);

        // F3：写盘后把索引状态置 STALE，驱动前端「索引已过期」徽标亮起，引导用户重建索引。
        markIndexStaleSafe(repoId);
        // Feature A：写盘后把该文件的理解债务标为 stale，下次 GET 触发重算（失败安全）。
        comprehensionDebtService.markDebtStale(repoId, target.getFilePath());
        // C P1: mark trace links STALE for modified file
        return markDechainSafe(repoId, target.getFilePath(), false);
    }

    /**
     * 把 repo 索引标记为 STALE（失败安全：任何异常静默吞掉，不影响 apply 主链路）。
     */
    private void markIndexStaleSafe(Long repoId) {
        try {
            RepoEntity update = new RepoEntity();
            update.setId(repoId);
            update.setIndexStatus(RepoIndexStatus.STALE);
            repoMapper.updateById(update);
            log.info("AUDIT mark-index-stale repo={}", repoId);
        } catch (Exception ex) {
            log.warn("mark-index-stale failed (non-fatal), repoId={}, err={}", repoId, ex.getMessage());
        }
    }

    /** 拒绝一条 PROPOSED 变更：仅置 REJECTED，不写盘。 */
    private void rejectOne(Long userId, Long repoId, FileChangeLogEntity target) {
        target.setStatus(FileChangeLogEntity.STATUS_REJECTED);
        target.setApprovedBy(userId);
        target.setApprovedAt(java.time.LocalDateTime.now());
        fileChangeLogMapper.updateById(target);
        log.info("AUDIT file-change-reject user={} repo={} changeId={} path={}",
                userId, repoId, target.getId(), target.getFilePath());
        // F: append provenance ledger record (failure-safe)
        provenanceService.appendRecordSafe(repoId, target.getId(), target.getFilePath(),
                target.getOldContent(), target.getNewContent(),
                target.getLlmCallId(), target.getAgentRunId(),
                "REJECTED", userId);
    }

    /** 载入并校验一条属于本仓库、且仍处于 PROPOSED 的变更。 */
    private FileChangeLogEntity loadProposed(Long repoId, Long changeId) {
        FileChangeLogEntity target = fileChangeLogMapper.selectById(changeId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Change not found: " + changeId);
        }
        if (!Objects.equals(target.getRepoId(), repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "Change does not belong to repo " + repoId);
        }
        if (!FileChangeLogEntity.STATUS_PROPOSED.equals(target.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "Change is not PROPOSED (status=" + target.getStatus() + "): " + changeId);
        }
        return target;
    }

    private List<FileChangeLogEntity> listProposed(Long repoId, Long sessionId, String branchId) {
        return fileChangeLogMapper.selectList(
                Wrappers.<FileChangeLogEntity>lambdaQuery()
                        .eq(FileChangeLogEntity::getRepoId, repoId)
                        .eq(sessionId != null, FileChangeLogEntity::getSessionId, sessionId)
                        .eq(StringUtils.hasText(branchId), FileChangeLogEntity::getBranchId, branchId)
                        .eq(FileChangeLogEntity::getStatus, FileChangeLogEntity.STATUS_PROPOSED)
                        .orderByAsc(FileChangeLogEntity::getId));
    }

    private FileChangeVO summary(FileChangeLogEntity c, List<Long> dechainedReqIds) {
        return FileChangeVO.builder()
                .id(c.getId())
                .filePath(c.getFilePath())
                .changeId(c.getId())
                .dechainedReqIds(dechainedReqIds == null ? List.of() : dechainedReqIds)
                .build();
    }

    /**
     * 失败安全地调用 TraceabilityService.markDechainSafe，任何异常静默吞掉。
     * @param fileDeleted true=文件被删除(→BROKEN)，false=文件被修改(→STALE)
     * @return 受影响的需求 ID 列表（失败时返回空列表）
     */
    private List<Long> markDechainSafe(Long repoId, String filePath, boolean fileDeleted) {
        try {
            return traceabilityService.markDechainSafe(repoId, filePath, fileDeleted);
        } catch (Exception ex) {
            log.warn("markDechainSafe failed (non-fatal), repoId={} path={} err={}", repoId, filePath, ex.getMessage());
            return List.of();
        }
    }

    @Override
    public FileChangeVO revert(Long userId, Long repoId, Long changeId) {
        ensureRepoPermission(userId, repoId);

        FileChangeLogEntity target = fileChangeLogMapper.selectById(changeId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "Change not found: " + changeId);
        }
        if (!Objects.equals(target.getRepoId(), repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "Change does not belong to repo " + repoId);
        }

        String restoreContent = target.getOldContent() == null ? "" : target.getOldContent();

        // 把旧内容写回文件：路径安全 / 大小上限 / 审计全在 RepoFileWriteService 内完成。
        // DELETE 变更回滚：文件已被删除，需用 createFile 重建（writeFile 要求文件已存在）。
        FileWriteRequest writeRequest = new FileWriteRequest();
        writeRequest.setRepoId(repoId);
        writeRequest.setFilePath(target.getFilePath());
        writeRequest.setContent(restoreContent);
        if (FileChangeLogEntity.OP_TYPE_DELETE.equals(target.getOpType())) {
            repoFileWriteService.createFile(userId, writeRequest);
        } else {
            repoFileWriteService.writeFile(userId, writeRequest);
        }

        // 标记原变更已回滚（reverted=1 保留兼容旧数据，status=REVERTED 为权威状态）。
        target.setReverted(1);
        target.setStatus(FileChangeLogEntity.STATUS_REVERTED);
        fileChangeLogMapper.updateById(target);

        // 追加一条回滚记录：old=当前 new（即被撤销的内容），new=恢复回去的旧内容。
        FileChangeLogEntity revertLog = new FileChangeLogEntity();
        revertLog.setRepoId(repoId);
        revertLog.setSessionId(target.getSessionId());
        revertLog.setFilePath(target.getFilePath());
        revertLog.setOldContent(target.getNewContent());
        revertLog.setNewContent(restoreContent);
        revertLog.setReverted(0);
        revertLog.setStatus(FileChangeLogEntity.STATUS_APPLIED);
        fileChangeLogMapper.insert(revertLog);

        log.info("AUDIT file-change-revert user={} repo={} changeId={} path={} newLogId={}",
                userId, repoId, changeId, target.getFilePath(), revertLog.getId());
        // F: append provenance ledger record for original change (failure-safe)
        provenanceService.appendRecordSafe(repoId, target.getId(), target.getFilePath(),
                target.getOldContent(), target.getNewContent(),
                target.getLlmCallId(), target.getAgentRunId(),
                "REVERTED", userId);

        return FileChangeVO.builder()
                .id(revertLog.getId())
                .filePath(revertLog.getFilePath())
                .changeId(revertLog.getId())
                .build();
    }

    private void ensureRepoPermission(Long userId, Long repoId) {
        if (!permissionService.checkRepoPermission(userId, repoId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "No repo permission");
        }
    }
}
