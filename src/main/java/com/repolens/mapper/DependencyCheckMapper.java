package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.DependencyCheckEntity;

public interface DependencyCheckMapper extends BaseMapper<DependencyCheckEntity> {

    /**
     * Delete all dependency_check rows for the given change_id.
     * Called before re-inserting results so that a re-check replaces rather than accumulates rows.
     */
    default int deleteByChangeId(Long changeId) {
        return delete(Wrappers.<DependencyCheckEntity>lambdaQuery()
                .eq(DependencyCheckEntity::getChangeId, changeId));
    }
}
