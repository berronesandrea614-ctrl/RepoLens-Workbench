package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class ChangeGraphVO {
    private List<ChangedFileVO> changedFiles;
    private List<GraphNodeVO> changedSymbols;
    private BlastSubgraphVO upstream;
    private BlastSubgraphVO downstream;
    private boolean truncated;
}
