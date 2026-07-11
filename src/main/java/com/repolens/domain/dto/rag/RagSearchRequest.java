package com.repolens.domain.dto.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RagSearchRequest {

    @NotBlank
    private String query;

    @Max(20)
    private Integer topK;
}
