package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.FileChangeLogEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface FileChangeLogMapper extends BaseMapper<FileChangeLogEntity> {

    /**
     * Feature F P1: 批量回填 agent_run_id。
     * 在 agent 循环结束、persistAgentRunSafe 返回 runId 后调用，
     * 把本次会话内所有 id > baseline 且 agent_run_id 为空的行一次性回填。
     */
    @Update("UPDATE file_change_log SET agent_run_id = #{agentRunId} " +
            "WHERE session_id = #{sessionId} AND id > #{baselineId} AND agent_run_id IS NULL")
    int updateAgentRunIdBySessionAndBaseline(
            @Param("sessionId") Long sessionId,
            @Param("baselineId") long baselineId,
            @Param("agentRunId") Long agentRunId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM file_change_log " +
            "WHERE session_id = #{sessionId} AND id > #{watermark}")
    int deleteBeyondWatermark(
            @Param("sessionId") Long sessionId,
            @Param("watermark") long watermark);

    @org.apache.ibatis.annotations.Select("SELECT COALESCE(MAX(id), 0) FROM file_change_log WHERE session_id = #{sessionId}")
    Long selectMaxIdBySession(@Param("sessionId") Long sessionId);
}

