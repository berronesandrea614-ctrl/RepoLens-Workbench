package com.repolens.domain.dto.symbol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FileContentRequest {

    @NotNull
    private Long repoId;

    @NotBlank
    private String filePath;

    @NotNull
    private Integer startLine;

    @NotNull
    private Integer endLine;
}
