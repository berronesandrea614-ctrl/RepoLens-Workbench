package com.repolens.kernel.loop;

import com.repolens.domain.enums.PermissionMode;
import com.repolens.kernel.spi.KernelTool;
import com.repolens.kernel.spi.ToolContext;
import com.repolens.llm.model.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具分发中枢（注册表模式）。取代旧版 1476 行 god switch：
 * Spring 自动注入所有 {@link KernelTool} bean，按 {@code name} 建表，主循环按名查表分发。
 * 加工具 = 加一个实现 {@code KernelTool} 的 bean，无需改动本类。
 *
 * <p>fail-safe：未知工具名、工具执行异常都以自然语言错误串返回（不抛），让 agent 读错自愈。
 */
@Component
public class ToolRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolRouter.class);

    /** name → tool，LinkedHashMap 保持注册顺序（工具目录展示稳定）。 */
    private final Map<String, KernelTool> tools = new LinkedHashMap<>();

    /** 消融实验：禁用的工具名（逗号分隔）——NO_VERIFY=runVerification、NO_TODO=TodoWrite、NO_BASH=bash。默认空=全开。 */
    @Value("${repolens.kernel.ablation.disabled-tools:}")
    private String disabledToolsCsv;

    /** 消融实验：THIN_DESC——把工具描述砍成只剩名字，测「详细工具描述」对工具用对率的贡献。默认关。 */
    @Value("${repolens.kernel.ablation.thin-desc:false}")
    private boolean thinDesc;

    private Set<String> disabledTools() {
        if (disabledToolsCsv == null || disabledToolsCsv.isBlank()) {
            return Set.of();
        }
        Set<String> s = new HashSet<>();
        for (String t : disabledToolsCsv.split(",")) {
            String n = t.trim();
            if (!n.isEmpty()) {
                s.add(n);
            }
        }
        return s;
    }

    /** THIN_DESC 开启时把描述砍成只剩名字（保留 name/parameters，供工具调用仍可解析）。 */
    private ToolDefinition maybeThin(ToolDefinition d) {
        if (!thinDesc) {
            return d;
        }
        ToolDefinition t = new ToolDefinition();
        t.setName(d.getName());
        t.setDescription(d.getName());
        t.setParameters(d.getParameters());
        return t;
    }

    public ToolRouter(List<KernelTool> kernelTools) {
        for (KernelTool t : kernelTools) {
            KernelTool prev = tools.put(t.name(), t);
            if (prev != null) {
                log.warn("[tool-router] 工具名冲突：{} 被 {} 覆盖", t.name(), t.getClass().getSimpleName());
            }
        }
        log.info("[tool-router] 注册 {} 个工具：{}", tools.size(), tools.keySet());
    }

    /** 该工具是否只读（决定并发/串行调度）；未知工具保守当作写类（串行更安全）。 */
    public boolean isReadOnly(String name) {
        KernelTool t = tools.get(name);
        return t != null && t.readOnly();
    }

    /** 已注册工具是否存在。 */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 全部工具定义（喂给 LLM 的工具目录）——应用消融开关（禁用工具/薄描述）。 */
    public List<ToolDefinition> definitions() {
        Set<String> off = disabledTools();
        return tools.values().stream()
                .filter(t -> !off.contains(t.name()))
                .map(t -> maybeThin(t.definition()))
                .toList();
    }

    /**
     * 按权限模式过滤后的工具目录（M4）：<b>PLAN 计划模式只暴露只读工具</b>（read/grep/glob），
     * 写/执行工具根本不进 catalog——从源头让 LLM 无从发起改动。其余模式暴露全部工具。
     *
     * <p>这是「模式贯通」的目录侧；执行侧另有 {@code KernelPermissionGate} 兜底（deny→ask→allow）。
     */
    public List<ToolDefinition> definitions(PermissionMode mode) {
        Set<String> off = disabledTools();
        return tools.values().stream()
                .filter(t -> !off.contains(t.name()))
                .filter(t -> mode != PermissionMode.PLAN || t.readOnly() || PLAN_CONTROL_TOOLS.contains(t.name()))
                .map(t -> maybeThin(t.definition()))
                .toList();
    }

    /**
     * PLAN 模式额外放行的「控制类」工具（M5）：不碰文件、不执行命令，只维护 agent 自身状态。
     * TodoWrite 声明 {@code readOnly=false}（表明它改状态），但计划阶段也需要它列/更新任务清单，
     * 故显式加入 PLAN 目录白名单——权限门侧亦把它归 A 级放行，两侧一致。
     */
    private static final java.util.Set<String> PLAN_CONTROL_TOOLS = java.util.Set.of("TodoWrite");

    /** 按名分发执行；未知工具/异常均返回错误串（fail-safe，不抛）。 */
    public String dispatch(String name, ToolContext ctx, Map<String, Object> args) {
        if (disabledTools().contains(name)) {
            return "工具 " + name + " 在当前配置下不可用（消融实验禁用）。";
        }
        KernelTool tool = tools.get(name);
        if (tool == null) {
            return "未知工具：" + name + "。可用工具：" + tools.keySet();
        }
        try {
            String result = tool.execute(ctx, args);
            return result == null ? "（工具无返回）" : result;
        } catch (Exception e) {
            log.warn("[tool-router] 工具 {} 执行异常", name, e);
            return name + " 执行异常：" + e.getMessage();
        }
    }
}
