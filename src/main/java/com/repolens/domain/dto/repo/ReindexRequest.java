package com.repolens.domain.dto.repo;

import lombok.Data;

@Data
public class ReindexRequest {

    /**
     * Optional. When empty, reuse repository default branch.
     */
    private String branchName;

    /**
     * Optional. MVP can omit this field and let backend generate UUID.
     */
    private String requestId;
}
