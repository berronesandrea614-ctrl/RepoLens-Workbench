package com.repolens.service;

import com.repolens.domain.vo.TraceForwardVO;
import com.repolens.domain.vo.TraceMapVO;
import com.repolens.domain.vo.TraceReverseVO;

import java.util.List;

/**
 * Feature C: 双向可追溯地图服务。
 *
 * <p>惰性快照：getOrComputeMap 有快照直接返回，recompute 强制重算并存快照。
 * 失败安全：向量/LLM 不可用时降级到 DECLARED-only + degraded=true。
 */
public interface TraceabilityService {

    /**
     * 获取仓库可追溯地图（惰性：有快照直接返回，否则计算后存快照）。
     */
    TraceMapVO getOrComputeMap(Long userId, Long repoId);

    /**
     * 强制重算可追溯地图（apply/revert 后调用）。
     */
    TraceMapVO recompute(Long userId, Long repoId);

    /**
     * 正向追溯：该需求被哪些代码符号实现。
     */
    TraceForwardVO forwardTrace(Long userId, Long repoId, Long requirementId);

    /**
     * 反向追溯（P1）：该符号属于哪些需求。
     */
    TraceReverseVO reverseTrace(Long userId, Long repoId, Long symbolId);

    /**
     * P1 脱钩检测（失败安全）：apply/revert 某文件后调用，
     * 把涉及该文件的 requirement_symbol 行标记 STALE/BROKEN，
     * 返回受影响的需求 id 列表（供前端 toast）。
     * filePath=null 时只做 BROKEN 清理（全库扫）。
     */
    List<Long> markDechainSafe(Long repoId, String filePath, boolean fileDeleted);
}
