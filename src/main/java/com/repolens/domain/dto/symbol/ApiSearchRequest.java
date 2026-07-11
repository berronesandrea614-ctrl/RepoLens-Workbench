package com.repolens.domain.dto.symbol;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ApiSearchRequest {

    @NotNull
    private Long repoId;

    @NotBlank
    private String apiPath;
}
