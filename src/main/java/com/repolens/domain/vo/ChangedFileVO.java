package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChangedFileVO {
    private String filePath;
    private String changeStatus;
    private Long changeLogId;
}
