package com.repolens.kernel.route;

import com.repolens.kernel.settings.SettingsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 多档模型路由（M7.3）：按 {@link LlmCallKind} 把一次 LLM 调用路由到合适档位的模型。
 *
 * <p>规则：
 * <ul>
 *   <li>{@link LlmCallKind#MAIN_REASONING} → 始终用主模型（本 run 的 modelName），行为不变；</li>
 *   <li>{@link LlmCallKind#CHORE} → 若配置了 chore-model 则走小模型，否则回退主模型（默认行为不变）。</li>
 * </ul>
 *
 * <p>chore-model 的来源（优先级从高到低）：<b>项目/用户 {@code .claude/settings.json} 的 {@code chore-model} 键
 * &gt; {@code repolens.kernel.chore-model} 配置项</b>。均未配置时回退主模型——即「不配 = 行为不变」。
 * 这样接线到 {@code TaskTool} 的子代理调用（CHORE）后，默认仍用主模型，配了才降档，零回归风险。
 */
@Component("kernelModelRouter")
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** settings.json 里的键名。 */
    public static final String SETTINGS_CHORE_MODEL = "chore-model";

    private final SettingsResolver settingsResolver;

    /** 全局默认 chore 模型（配置项，可空）。settings.json 未配时的回退来源。 */
    @Value("${repolens.kernel.chore-model:}")
    private String choreModelDefault;

    public ModelRouter(SettingsResolver settingsResolver) {
        this.settingsResolver = settingsResolver;
    }

    /**
     * 解析某类调用应使用的模型名。
     *
     * @param kind        调用用途
     * @param mainModel   本 run 的主模型（回退目标）
     * @param repoDir     仓库根（读项目级 settings；可空）
     * @return 生效模型名（never null，至少回退到 mainModel）
     */
    public String modelFor(LlmCallKind kind, String mainModel, Path repoDir) {
        if (kind != LlmCallKind.CHORE) {
            return mainModel;
        }
        String chore = resolveChoreModel(repoDir);
        if (chore != null && !chore.isBlank()) {
            log.debug("[model-router] CHORE 调用路由到小模型 {}（主模型 {}）", chore, mainModel);
            return chore;
        }
        return mainModel;
    }

    /** settings.json 的 chore-model 优先，其次配置默认；都无返回 null。 */
    private String resolveChoreModel(Path repoDir) {
        String fromSettings = settingsResolver.get(repoDir, SETTINGS_CHORE_MODEL);
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings.trim();
        }
        return choreModelDefault == null || choreModelDefault.isBlank() ? null : choreModelDefault.trim();
    }
}
