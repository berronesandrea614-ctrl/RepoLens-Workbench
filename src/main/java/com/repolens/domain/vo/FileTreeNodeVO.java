package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FileTreeNodeVO {
    private String name;
    private String path;
    private boolean directory;
    private List<FileTreeNodeVO> children;
}
