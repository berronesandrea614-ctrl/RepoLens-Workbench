package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SearchMatchVO {
    private String filePath;
    private int line;        // 1-based
    private String lineContent; // 截断到 200 字符
    private int startCol;    // 0-based 命中起点
}
