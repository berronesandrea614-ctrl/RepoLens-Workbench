package com.repolens.service;

import com.repolens.common.exception.BizException;

/**
 * 应用层出网策略网关（Feature G 代码0字节出网可验证）。
 *
 * <p>每次应用层发起外部网络连接前调用 {@link #checkAndLog}，
 * 服务会：① 解析 host 的实际 IP；② 判断是否为回环地址（复用 SsrfGuard 地址分类逻辑）；
 * ③ 按当前隐私模式决定是否放行；④ 写一条 egress_log 记录（失败安全——写入失败绝不阻断主流程）。
 *
 * <p><strong>边界说明</strong>：本网关只拦截"经本应用层发起"的出站连接，不覆盖
 * OS 进程层面的其他出网路径（如 JVM 内置遥测、OS 更新等）。完整的零出网证明需配合
 * JFR/OS 层工具（lsof/tcpdump/Little Snitch）交叉验证。
 */
public interface EgressPolicy {

    /**
     * 检查并记录一次出网尝试。
     *
     * <p>模式行为：
     * <ul>
     *   <li><b>OPEN</b>：全放行，记录 allowed=1。</li>
     *   <li><b>ALLOWLIST</b>：回环 or 白名单内则放行（allowed=1），否则记录 allowed=0 并抛出 BizException。</li>
     *   <li><b>LOCAL_ONLY</b>：回环则放行（allowed=1），否则记录 allowed=0 并抛出 BizException。</li>
     * </ul>
     *
     * @param host      目标主机名（或 IP）
     * @param port      目标端口（≤0 表示未知）
     * @param purpose   出网用途标识（LLM / EMBEDDING / GIT_CLONE / DEP_CHECK 等）
     * @param modelName 关联模型名称（LLM/EMBEDDING 场景，其他传 null）
     * @throws BizException 当前模式不允许向此主机出网时抛出（FORBIDDEN 403）
     */
    void checkAndLog(String host, int port, String purpose, String modelName);

    /**
     * 返回当前隐私模式（LOCAL_ONLY / ALLOWLIST / OPEN）。
     * 若未配置则返回默认值 OPEN（向后兼容）。
     */
    String getMode();
}
