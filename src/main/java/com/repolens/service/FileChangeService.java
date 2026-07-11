package com.repolens.service;

import com.repolens.domain.vo.FileChangeDetailVO;
import com.repolens.domain.vo.FileChangeVO;

import java.util.List;

/**
 * 文件变更查看与回滚服务（编码模式产物的读侧 + 撤销）。
 */
public interface FileChangeService {

    /** 列出某仓库（可按会话过滤）的文件变更明细（含 status，含 PROPOSED），权限门控。 */
    List<FileChangeDetailVO> listChanges(Long userId, Long repoId, Long sessionId);

    /**
     * 审批通过一条 PROPOSED 变更（写盘门），不带 ack 标志（等价于 ack=false）。
     * 向下兼容入口，委托给带 ack 参数的重载。
     */
    default FileChangeVO apply(Long userId, Long repoId, Long changeId) {
        return apply(userId, repoId, changeId, false);
    }

    /**
     * 审批通过一条 PROPOSED 变更（写盘门）：把其 newContent 经 RepoFileWriteService 写盘，
     * 状态置为 APPLIED。权限门控 + 路径安全。
     * <p>ack=false 时若存在未确认的 BLOCK 级风险，抛出 BizException(BAD_REQUEST)。</p>
     *
     * @param ack 用户已勾选确认风险标志
     * @return 被 APPLIED 的变更摘要
     */
    FileChangeVO apply(Long userId, Long repoId, Long changeId, boolean ack);

    /**
     * 拒绝一条 PROPOSED 变更：仅把状态置为 REJECTED，不做任何写盘。权限门控。
     *
     * @return 被 REJECTED 的变更摘要
     */
    FileChangeVO reject(Long userId, Long repoId, Long changeId);

    /**
     * 审批通过某会话下全部 PROPOSED 变更，不带 ack 标志（等价于 ack=false）。
     * 向下兼容入口，委托给带 ack 参数的重载。
     */
    default List<FileChangeVO> applyAll(Long userId, Long repoId, Long sessionId) {
        return applyAll(userId, repoId, sessionId, false);
    }

    /**
     * 审批通过某会话下全部 PROPOSED 变更（逐条写盘），返回被 APPLIED 的变更摘要。权限门控。
     * <p>ack=false 时若任一变更存在未确认 BLOCK，整体抛出异常，请先逐条确认。</p>
     * <p>向下兼容：委托给带 branchId 参数的重载，branchId=null 表示不过滤（apply 该 session 全部 PROPOSED）。</p>
     *
     * @param ack 用户已勾选确认风险标志
     */
    default List<FileChangeVO> applyAll(Long userId, Long repoId, Long sessionId, boolean ack) {
        return applyAll(userId, repoId, sessionId, (String) null, ack);
    }

    /**
     * 审批通过某会话下指定分支的全部 PROPOSED 变更（逐条写盘），返回被 APPLIED 的变更摘要。权限门控。
     * <p>branchId=null/空 → 不加 branchId 过滤，apply 该 session 全部 PROPOSED（同旧行为）。</p>
     *
     * @param branchId 分支 ID 过滤；null 或空字符串表示不过滤
     * @param ack      用户已勾选确认风险标志
     */
    List<FileChangeVO> applyAll(Long userId, Long repoId, Long sessionId, String branchId, boolean ack);

    /** 拒绝某会话下全部 PROPOSED 变更（不写盘），返回被 REJECTED 的变更摘要。权限门控。 */
    List<FileChangeVO> rejectAll(Long userId, Long repoId, Long sessionId);

    /**
     * 回滚一条已 APPLIED 的变更：把该变更的旧内容写回文件，标记其 reverted=1 且 status=REVERTED，
     * 并追加一条“回滚”记录（old=当前 new，new=原 old）。权限门控 + 路径安全。
     *
     * @return 新追加的回滚记录摘要
     */
    FileChangeVO revert(Long userId, Long repoId, Long changeId);
}
