package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.ChangeRiskFlagEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * Mapper for {@link ChangeRiskFlagEntity}.
 */
public interface ChangeRiskFlagMapper extends BaseMapper<ChangeRiskFlagEntity> {

    /**
     * Delete all change_risk_flag rows for the given change_id.
     * Used in delete-before-insert pattern to replace stale scan results.
     */
    default int deleteByChangeId(Long changeId) {
        return delete(Wrappers.<ChangeRiskFlagEntity>lambdaQuery()
                .eq(ChangeRiskFlagEntity::getChangeId, changeId));
    }

    /**
     * Mark all flags for the given change as acknowledged.
     * Uses a plain annotated update to avoid MyBatis-Plus lambda cache requirements in unit tests.
     */
    @Update("UPDATE change_risk_flag SET acknowledged = 1, acknowledged_by = #{userId}, " +
            "acknowledged_at = #{acknowledgedAt} WHERE change_id = #{changeId}")
    int acknowledgeByChangeId(@Param("userId") Long userId,
                              @Param("changeId") Long changeId,
                              @Param("acknowledgedAt") LocalDateTime acknowledgedAt);
}
