package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.EgressLogEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 出网审计日志读写 Mapper（Feature G）。
 *
 * <p>BaseMapper 提供 insert/selectById/selectList 等基础操作；
 * 额外提供统计用 count 查询供 PrivacyStatusVO 构建，以及 P2 校验/报告所需聚合查询。
 */
@Mapper
public interface EgressLogMapper extends BaseMapper<EgressLogEntity> {

    /** 统计全部记录数（用于 /status 徽标计数）。 */
    @Select("SELECT COUNT(*) FROM egress_log")
    long countTotal();

    /** 统计被拦截记录数（allowed=0）。 */
    @Select("SELECT COUNT(*) FROM egress_log WHERE allowed = 0")
    long countBlocked();

    // ─── G-P2 校验与报告 ──────────────────────────────────────────────────────

    /**
     * 统计指定时间点之后，allowed=1 且 is_loopback=0 的记录数。
     * 用于「recentEgressAllExternalBlocked」检查：LOCAL_ONLY 下该数必须为 0。
     */
    @Select("SELECT COUNT(*) FROM egress_log WHERE ts >= #{since} AND allowed = 1 AND is_loopback = 0")
    long countRecentNonLoopbackAllowed(@Param("since") LocalDateTime since);

    /**
     * 获取指定时间点之后出现过的所有非回环目标主机列表（去重，最多 50 个）。
     * 用于报告中展示"曾尝试出网的外部主机"。
     */
    @Select("SELECT DISTINCT dest_host FROM egress_log WHERE ts >= #{since} AND is_loopback = 0 ORDER BY dest_host LIMIT 50")
    List<String> recentExternalHosts(@Param("since") LocalDateTime since);

    /**
     * 获取指定时间点之后，allowed=1 且 is_loopback=0 的目标主机列表（去重，最多 50 个）。
     * 用于报告中展示"实际放行的外部主机"（LOCAL_ONLY 下应为空）。
     */
    @Select("SELECT DISTINCT dest_host FROM egress_log WHERE ts >= #{since} AND is_loopback = 0 AND allowed = 1 ORDER BY dest_host LIMIT 50")
    List<String> recentExternalAllowedHosts(@Param("since") LocalDateTime since);

    /** 最早记录时间（用于报告时间范围，无数据返回 null）。 */
    @Select("SELECT MIN(ts) FROM egress_log")
    LocalDateTime minTs();

    /** 最新记录时间（用于报告时间范围，无数据返回 null）。 */
    @Select("SELECT MAX(ts) FROM egress_log")
    LocalDateTime maxTs();
}
