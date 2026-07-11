package com.repolens.domain.vo;

import com.repolens.domain.enums.RepoIndexStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class RepoVO {

    private Long id;
    private Long workspaceId;
    private String repoName;
    private String repoUrl;
    private String branchName;
    private String latestCommitId;
    private RepoIndexStatus indexStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
