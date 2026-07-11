package com.repolens.domain.dto.repo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateRepoRequest {

    @NotNull
    private Long workspaceId;

    @NotBlank
    private String repoName;

    @NotBlank
    private String repoUrl;

    private String branchName;
}
