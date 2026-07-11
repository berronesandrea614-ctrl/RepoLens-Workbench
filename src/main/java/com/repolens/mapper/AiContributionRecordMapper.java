package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.AiContributionRecordEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AI 贡献溯源账本 Mapper（Feature F）。
 */
public interface AiContributionRecordMapper extends BaseMapper<AiContributionRecordEntity> {

    /**
     * 查询 repo 最大 seq（用于生成下一条的 seq = max+1）。
     * 无记录时返回 null。
     */
    @Select("SELECT MAX(seq) FROM ai_contribution_record WHERE repo_id = #{repoId}")
    Long selectMaxSeqByRepoId(@Param("repoId") Long repoId);

    /**
     * 查询 repo 最新一条记录的 record_hash（用于哈希链串联）。
     * 按 seq 降序取第一行；无记录时返回 null。
     */
    @Select("SELECT record_hash FROM ai_contribution_record WHERE repo_id = #{repoId} ORDER BY seq DESC LIMIT 1")
    String selectLastRecordHash(@Param("repoId") Long repoId);

    /**
     * 按 seq 升序查询 repo 的所有账本记录（用于哈希链校验）。
     */
    @Select("SELECT * FROM ai_contribution_record WHERE repo_id = #{repoId} ORDER BY seq ASC")
    List<AiContributionRecordEntity> selectAllByRepoIdOrderBySeq(@Param("repoId") Long repoId);

    /**
     * 分页查询 repo 账本（按 seq 降序，最新在前）。
     */
    @Select("SELECT * FROM ai_contribution_record WHERE repo_id = #{repoId} ORDER BY seq DESC LIMIT #{size} OFFSET #{offset}")
    List<AiContributionRecordEntity> selectPageByRepoId(
            @Param("repoId") Long repoId,
            @Param("offset") int offset,
            @Param("size") int size);

    /**
     * 查询 repo 账本总数。
     */
    @Select("SELECT COUNT(*) FROM ai_contribution_record WHERE repo_id = #{repoId}")
    long countByRepoId(@Param("repoId") Long repoId);
}
