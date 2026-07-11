package com.repolens.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileWriteRequest {
    @NotNull
    private Long repoId;
    @NotBlank
    private String filePath;
    @NotNull
    private String content; // 允许空串，不允许 null
}
