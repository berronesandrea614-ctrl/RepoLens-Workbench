package com.repolens.service;

import com.repolens.domain.dto.repo.CreateRepoRequest;
import com.repolens.domain.dto.repo.ReindexRequest;
import com.repolens.domain.vo.IndexTaskVO;
import com.repolens.domain.vo.RepoVO;

import java.util.List;

public interface RepoService {

    RepoVO createRepo(Long userId, CreateRepoRequest request);

    /**
     * 列出当前用户可访问的全部仓库。
     * 访问规则与 PermissionService.checkRepoPermission 完全一致：
     * repo 权限回落为 workspace 成员关系，即只返回用户所属 workspace 下的仓库。
     */
    List<RepoVO> listRepos(Long userId);

    RepoVO getRepo(Long userId, Long repoId);

    List<IndexTaskVO> listRepoTasks(Long userId, Long repoId);

    IndexTaskVO reindexRepo(Long userId, Long repoId, ReindexRequest request);

    /**
     * 删除仓库及其全部衍生数据。
     * 权限受控，DB 级级联删除（code_file / symbol / dependency / chunk / index_task /
     * requirement(+symbol) / 各类日志 / agent_memory / chat_session(+message) / repo）在事务内完成；
     * 事务提交后再尽力清理 Milvus 向量与磁盘工作副本（best-effort，失败仅记录日志）。
     */
    void deleteRepo(Long userId, Long repoId);
}
