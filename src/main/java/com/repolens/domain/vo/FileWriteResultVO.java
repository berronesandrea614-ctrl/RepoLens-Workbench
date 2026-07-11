package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileWriteResultVO {
    private String filePath;
    private int bytes;
}
