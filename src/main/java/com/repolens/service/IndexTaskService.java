package com.repolens.service;

import com.repolens.domain.entity.IndexTaskEntity;
import com.repolens.domain.enums.TaskType;
import com.repolens.domain.vo.IndexTaskVO;

import java.util.List;

public interface IndexTaskService {

    IndexTaskEntity createInitCloneTask(Long repoId, String branchName);

    IndexTaskEntity createReindexCloneTask(Long repoId, String branchName, String requestId);

    IndexTaskEntity createManualImportCloneTask(Long repoId, String branchName);

    String buildCommitStageIdempotentKey(Long repoId, String commitId, TaskType taskType);

    List<IndexTaskVO> listByRepoId(Long repoId);

    IndexTaskEntity findLatestCloneTaskForImport(Long repoId);
}
