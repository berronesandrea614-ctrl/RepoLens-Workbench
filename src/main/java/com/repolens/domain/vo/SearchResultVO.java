package com.repolens.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class SearchResultVO {
    private String query;
    private List<SearchMatchVO> matches;
    private int matchCount;
    private boolean truncated;
    private int offset;
    private int limit;
    private boolean hasMore;
}
