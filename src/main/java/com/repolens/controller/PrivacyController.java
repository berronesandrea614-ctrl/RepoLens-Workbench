package com.repolens.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.result.Result;
import com.repolens.domain.dto.PrivacyModeRequest;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.domain.vo.EgressLogVO;
import com.repolens.domain.vo.PrivacyStatusVO;
import com.repolens.domain.vo.PrivacyVerifyVO;
import com.repolens.mapper.AppSettingMapper;
import com.repolens.mapper.EgressLogMapper;
import com.repolens.security.AuthUserId;
import com.repolens.service.EgressPolicy;
import com.repolens.service.impl.EgressPolicyImpl;
import com.repolens.service.impl.PrivacyVerifyServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * 隐私与出网控制接口（Feature G 代码0字节出网可验证）。
 *
 * <ul>
 *   <li>GET  /api/privacy/status — 徽标数据：当前模式 + 出网计数</li>
 *   <li>GET  /api/privacy/egress — 出网时间线（最近 N 条，默认 50）</li>
 *   <li>PUT  /api/privacy/mode   — 切换隐私模式（P1）</li>
 *   <li>GET  /api/privacy/verify — 四项链路校验（G-P2）</li>
 *   <li>GET  /api/privacy/report?format=json|txt — 0出网证明导出（G-P2）</li>
 * </ul>
 *
 * <p><b>边界声明</b>：本接口展示的是应用层网关拦截记录，
 * 不代表 OS/JVM 层的完整出站流量统计（见 {@link EgressPolicy} 文档）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/privacy")
public class PrivacyController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final EgressPolicy egressPolicy;
    private final EgressLogMapper egressLogMapper;
    private final AppSettingMapper appSettingMapper;
    private final PrivacyVerifyServiceImpl privacyVerifyService;

    /**
     * 获取隐私状态徽标数据。
     * 不需要 repo 权限：徽标是全局的，任何已登录用户均可读取。
     */
    @GetMapping("/status")
    public Result<PrivacyStatusVO> getStatus(@AuthUserId Long userId) {
        long total = countSafe(() -> egressLogMapper.countTotal(), 0L);
        long blocked = countSafe(() -> egressLogMapper.countBlocked(), 0L);
        // 读取白名单供前端设置页展示（失败安全）
        String allowlist = null;
        try {
            AppSettingEntity al = appSettingMapper.selectById(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST);
            if (al != null) {
                allowlist = al.getV();
            }
        } catch (Exception ex) {
            // fail-safe: allowlist 读取失败不影响主体响应
        }
        return Result.success(PrivacyStatusVO.builder()
                .mode(egressPolicy.getMode())
                .totalCount(total)
                .blockedCount(blocked)
                .allowedCount(total - blocked)
                .allowlist(allowlist)
                .build());
    }

    /**
     * 获取出网时间线（最近 N 条，降序）。
     */
    @GetMapping("/egress")
    public Result<List<EgressLogVO>> getEgress(
            @AuthUserId Long userId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        int safeLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
        List<EgressLogEntity> rows = egressLogMapper.selectList(
                Wrappers.<EgressLogEntity>lambdaQuery()
                        .orderByDesc(EgressLogEntity::getTs)
                        .last("LIMIT " + safeLimit));
        List<EgressLogVO> result = rows.stream().map(this::toVO).collect(Collectors.toList());
        return Result.success(result);
    }

    /**
     * 切换隐私模式（P1）。
     * LOCAL_ONLY：所有非回环出网请求被拦截。
     * ALLOWLIST ：仅允许回环或白名单内的主机。
     * OPEN      ：全放行，但仍记录审计日志。
     */
    @PutMapping("/mode")
    public Result<PrivacyStatusVO> updateMode(
            @AuthUserId Long userId,
            @RequestBody PrivacyModeRequest request) {

        if (request == null || !StringUtils.hasText(request.getMode())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "mode is required (LOCAL_ONLY / ALLOWLIST / OPEN)");
        }
        String newMode = request.getMode().trim().toUpperCase();
        if (!newMode.equals(EgressLogEntity.MODE_LOCAL_ONLY)
                && !newMode.equals(EgressLogEntity.MODE_ALLOWLIST)
                && !newMode.equals(EgressLogEntity.MODE_OPEN)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "Invalid mode: " + newMode + ". Must be LOCAL_ONLY / ALLOWLIST / OPEN");
        }

        // 持久化模式
        upsertSetting(EgressPolicyImpl.KEY_PRIVACY_MODE, newMode);

        // 更新白名单（ALLOWLIST 模式下写入，其他模式保留原值）
        if (EgressLogEntity.MODE_ALLOWLIST.equals(newMode) && request.getAllowlist() != null) {
            upsertSetting(EgressPolicyImpl.KEY_PRIVACY_ALLOWLIST, request.getAllowlist().trim());
        }

        // 返回新状态
        return getStatus(userId);
    }

    // ─── G-P2: verify + report ────────────────────────────────────────────────

    /**
     * 一键校验本地链路（G-P2）。
     * 执行四项检查（provider、baseUrl、Ollama 可达、出网日志），返回 PASS/FAIL 详情。
     * 失败安全：任何探针异常均返回 FAIL + 原因，不崩溃。
     */
    @GetMapping("/verify")
    public Result<PrivacyVerifyVO> verify(@AuthUserId Long userId) {
        PrivacyVerifyVO result = privacyVerifyService.verify();
        return Result.success(result);
    }

    /**
     * 导出零出网证明（G-P2）。
     *
     * <p>format=json（默认）— 返回结构化 JSON（含 verify 结果 + 出网摘要 + 边界说明 + JFR 指引）。
     * <p>format=txt — 返回可下载的纯文本/Markdown 证明报告（Content-Disposition: attachment）。
     * 不引入 PDF 重型依赖；可下载文本证明已满足"可导出、可存档"需求。
     *
     * <p><b>边界说明</b>：本报告为应用层网关快照；完整零出网证明需配合
     * JFR/OS 层工具（jcmd/jfr/lsof/tcpdump）交叉验证，方法见报告末尾。
     */
    @GetMapping("/report")
    public ResponseEntity<?> report(
            @AuthUserId Long userId,
            @RequestParam(value = "format", defaultValue = "json") String format) {

        PrivacyVerifyVO verify = privacyVerifyService.verify();

        if ("txt".equalsIgnoreCase(format) || "md".equalsIgnoreCase(format) || "pdf".equalsIgnoreCase(format)) {
            // 生成可下载的文本证明报告（text/plain + attachment；不引入重型 PDF 依赖）
            String report = buildTextReport(verify);
            String filename = "repolens-privacy-proof-" +
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(report);
        }

        // 默认 JSON：聚合所有报告数据
        java.util.Map<String, Object> report = buildJsonReport(verify);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Result.success(report));
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private EgressLogVO toVO(EgressLogEntity e) {
        return EgressLogVO.builder()
                .id(e.getId())
                .ts(e.getTs())
                .purpose(e.getPurpose())
                .destHost(e.getDestHost())
                .destPort(e.getDestPort())
                .resolvedIp(e.getResolvedIp())
                .loopback(Boolean.TRUE.equals(e.getIsLoopback()))
                .allowed(Boolean.TRUE.equals(e.getAllowed()))
                .privacyMode(e.getPrivacyMode())
                .modelName(e.getModelName())
                .bytesOut(e.getBytesOut())
                .build();
    }

    private void upsertSetting(String key, String value) {
        AppSettingEntity entity = new AppSettingEntity();
        entity.setK(key);
        entity.setV(value);
        if (appSettingMapper.selectById(key) != null) {
            appSettingMapper.updateById(entity);
        } else {
            appSettingMapper.insert(entity);
        }
    }

    @FunctionalInterface
    private interface LongSupplier {
        long get();
    }

    private long countSafe(LongSupplier fn, long fallback) {
        try {
            return fn.get();
        } catch (Exception ex) {
            return fallback;
        }
    }

    /** 生成结构化 JSON 报告数据（含出网摘要、verify 结果、边界说明、JFR 指引）。 */
    private java.util.Map<String, Object> buildJsonReport(PrivacyVerifyVO verify) {
        java.util.Map<String, Object> report = new java.util.LinkedHashMap<>();
        report.put("reportType", "RepoLens 代码0出网应用层证明");
        report.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        report.put("mode", verify.getMode());
        report.put("verdict", verify.isVerdict() ? "PASS" : "FAIL");
        report.put("verify", verify);

        // 出网摘要
        java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("totalCount", countSafe(() -> egressLogMapper.countTotal(), 0L));
        summary.put("blockedCount", countSafe(() -> egressLogMapper.countBlocked(), 0L));
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        try {
            summary.put("last24hExternalHostsSeen", egressLogMapper.recentExternalHosts(since24h));
            summary.put("last24hExternalHostsAllowed", egressLogMapper.recentExternalAllowedHosts(since24h));
            LocalDateTime minTs = egressLogMapper.minTs();
            LocalDateTime maxTs = egressLogMapper.maxTs();
            summary.put("logRangeFrom", minTs != null ? minTs.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
            summary.put("logRangeTo", maxTs != null ? maxTs.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        } catch (Exception ex) {
            summary.put("summaryError", "出网摘要查询失败（失败安全）: " + ex.getMessage());
        }
        report.put("egressSummary", summary);

        report.put("boundaryDisclaimer",
                "本报告为应用层出网网关（EgressPolicy）快照，仅证明经本应用 Java 代码路径发起的出站连接。" +
                "不覆盖 OS 进程层面的其他出网路径（JVM 遥测/系统更新/网络代理等）。" +
                "完整零出网证明需配合 JVM 层（JFR jdk.SocketWrite/jdk.SocketRead）及 " +
                "OS 层（lsof -i TCP/UDP / tcpdump / Little Snitch）交叉验证。");
        report.put("jfrInstructions", jfrInstructions());
        report.put("jfrScriptPath", "scripts/jfr-privacy-check.sh");
        return report;
    }

    /** 生成可下载的纯文本/Markdown 格式证明报告。 */
    private String buildTextReport(PrivacyVerifyVO verify) {
        StringBuilder sb = new StringBuilder();
        String line = "=".repeat(64);
        String dash = "-".repeat(64);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        sb.append(line).append("\n");
        sb.append("  RepoLens 代码0出网 — 应用层证明报告\n");
        sb.append("  生成时间: ").append(ts).append("\n");
        sb.append(line).append("\n\n");

        sb.append("## 总体裁决\n");
        sb.append("  ").append(verify.isVerdict() ? "✓ PASS" : "✗ FAIL").append("\n");
        sb.append("  当前模式: ").append(verify.getMode()).append("\n\n");

        sb.append("## 四项链路检查\n").append(dash).append("\n");
        appendCheck(sb, "1. LLM Provider 本地化", verify.getLlmProviderIsLocal());
        appendCheck(sb, "2. BaseUrl 回环验证",    verify.getBaseUrlIsLoopback());
        appendCheck(sb, "3. Ollama 端点可达",      verify.getOllamaReachable());
        appendCheck(sb, "4. 出网日志外网放行为零",  verify.getRecentEgressAllExternalBlocked());
        sb.append("\n");

        // 出网摘要
        sb.append("## 出网日志摘要（应用层）\n").append(dash).append("\n");
        long total = countSafe(() -> egressLogMapper.countTotal(), -1L);
        long blocked = countSafe(() -> egressLogMapper.countBlocked(), -1L);
        sb.append("  累计记录: ").append(total >= 0 ? total : "查询失败").append(" 条\n");
        sb.append("  已拦截:   ").append(blocked >= 0 ? blocked : "查询失败").append(" 条\n");
        sb.append("  放行:     ").append(total >= 0 && blocked >= 0 ? (total - blocked) : "查询失败").append(" 条\n");
        LocalDateTime since24h = LocalDateTime.now().minusHours(24);
        try {
            List<String> extSeen = egressLogMapper.recentExternalHosts(since24h);
            List<String> extAllowed = egressLogMapper.recentExternalAllowedHosts(since24h);
            sb.append("  最近24h 外部主机（尝试）: ").append(extSeen.isEmpty() ? "无" : String.join(", ", extSeen)).append("\n");
            sb.append("  最近24h 外部主机（放行）: ").append(extAllowed.isEmpty() ? "无" : String.join(", ", extAllowed)).append("\n");
        } catch (Exception ex) {
            sb.append("  出网摘要查询失败（失败安全）: ").append(ex.getMessage()).append("\n");
        }
        sb.append("\n");

        sb.append("## 边界声明\n").append(dash).append("\n");
        sb.append("  本报告为应用层出网网关（EgressPolicy）快照，\n");
        sb.append("  仅证明经本应用 Java 代码路径发起的出站连接。\n");
        sb.append("  不覆盖 OS 进程层面的其他出网路径（JVM 遥测/系统更新等）。\n");
        sb.append("  完整零出网证明需配合以下 JVM/OS 层工具交叉验证。\n\n");

        sb.append("## JFR 交叉验证指引\n").append(dash).append("\n");
        sb.append(jfrInstructions()).append("\n");
        sb.append("  JFR 辅助脚本: scripts/jfr-privacy-check.sh\n\n");

        sb.append(line).append("\n");
        sb.append("  本报告由 RepoLens 自动生成，仅供参考。\n");
        sb.append(line).append("\n");
        return sb.toString();
    }

    private static void appendCheck(StringBuilder sb, String title, PrivacyVerifyVO.CheckResult r) {
        if (r == null) {
            sb.append("  ").append(title).append(": N/A\n");
            return;
        }
        sb.append("  ").append(r.isPassed() ? "✓" : "✗").append(" ").append(title).append("\n");
        sb.append("    → ").append(r.getReason()).append("\n");
    }

    private static String jfrInstructions() {
        return """
                步骤1: 获取 RepoLens 后端 PID
                  jcmd | grep repolens   （或 pgrep -f repolens）

                步骤2: 开始 JFR 录制（捕获 Socket 事件，持续 60s）
                  jcmd <PID> JFR.start name=privacy-check \\
                    settings=profile duration=60s \\
                    filename=/tmp/repolens-privacy.jfr \\
                    jdk.SocketWrite#enabled=true jdk.SocketRead#enabled=true

                步骤3: 等待录制完成后，导出可读报告
                  jfr print --events jdk.SocketWrite,jdk.SocketRead \\
                    /tmp/repolens-privacy.jfr | grep -v "::1\\\\|127\\\\." | head -40

                步骤4: 期望输出
                  LOCAL_ONLY 模式下：无任何非回环 SocketWrite/SocketRead 事件。
                  若有输出则说明存在 OS 层面出网（需进一步排查 JVM 遥测、系统代理等）。

                OS 层补充:
                  lsof -i TCP -n -P | grep <PID>    （检查进程开放端口）
                  tcpdump -i any -n host not 127.0.0.1 and host not ::1 and pid <PID>

                参考脚本: scripts/jfr-privacy-check.sh""";
    }
}
