package com.repolens.llm.config;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.domain.entity.EgressLogEntity;
import com.repolens.mapper.AppSettingMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

/**
 * 运行时 LLM 配置的单一真源。
 *
 * <p>设计：环境变量/application.yml 只提供【默认值】；真正被各 LLM 读取点消费的是这里的
 * volatile 字段。启动时先用 {@code @Value} 默认播种，再用 app_setting 表里已存的覆盖值覆盖，
 * 使得用户在应用内改的配置能跨重启持久生效。没有任何 app_setting 行时，行为与仅用环境变量
 * 默认完全一致。
 *
 * <p>安全边界：api-key 只在内存与 HTTP Header 中出现；本类不打印 api-key，读接口返回时由上层脱敏。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRuntimeConfig {

    public static final String KEY_PROVIDER = "llm.provider";
    public static final String KEY_BASE_URL = "llm.base-url";
    public static final String KEY_API_KEY = "llm.api-key";
    public static final String KEY_MODEL_NAME = "llm.model-name";
    public static final String KEY_TIMEOUT_MS = "llm.timeout-ms";

    private final AppSettingMapper appSettingMapper;

    @Value("${repolens.llm.provider:mock}")
    private String defaultProvider;

    @Value("${repolens.llm.base-url:}")
    private String defaultBaseUrl;

    @Value("${repolens.llm.api-key:}")
    private String defaultApiKey;

    @Value("${repolens.llm.model-name:mock-code-assistant}")
    private String defaultModelName;

    @Value("${repolens.llm.timeout-ms:15000}")
    private int defaultTimeoutMs;

    private volatile String provider;
    private volatile String baseUrl;
    private volatile String apiKey;
    private volatile String modelName;
    private volatile int timeoutMs;

    /**
     * 先用环境默认播种全部字段，再尝试用 app_setting 覆盖。
     * 覆盖读取整体 try/catch 失败安全：即便表缺失/DB 不可用，也能用默认值正常启动。
     */
    @PostConstruct
    public void init() {
        this.provider = defaultProvider;
        this.baseUrl = defaultBaseUrl;
        this.apiKey = defaultApiKey;
        this.modelName = defaultModelName;
        this.timeoutMs = defaultTimeoutMs;

        try {
            List<AppSettingEntity> rows = appSettingMapper.selectList(null);
            if (rows == null) {
                return;
            }
            for (AppSettingEntity row : rows) {
                if (row == null || row.getK() == null) {
                    continue;
                }
                String v = row.getV();
                switch (row.getK()) {
                    case KEY_PROVIDER -> { if (StringUtils.hasText(v)) this.provider = v.trim(); }
                    case KEY_BASE_URL -> { if (v != null) this.baseUrl = v.trim(); }
                    case KEY_API_KEY -> { if (v != null) this.apiKey = v.trim(); }
                    case KEY_MODEL_NAME -> { if (StringUtils.hasText(v)) this.modelName = v.trim(); }
                    case KEY_TIMEOUT_MS -> {
                        Integer parsed = parseTimeout(v);
                        if (parsed != null) this.timeoutMs = parsed;
                    }
                    default -> { /* 忽略未知键 */ }
                }
            }
        } catch (Exception ex) {
            // 表缺失/DB 不可用等：保留环境默认，绝不阻断应用启动。api-key 绝不入日志。
            log.warn("load app_setting overrides failed, keep env defaults, err={}", ex.getMessage());
        }
    }

    public String getProvider() {
        return provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModelName() {
        return modelName;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    /**
     * 运行时更新并持久化。约定：
     * - provider/modelName 仅在非空白时更新（避免误清空必需项）；
     * - baseUrl 非 null 即更新（允许显式清空以切回 mock）；
     * - apiKey 为 null 或空白时【保留现有值】，不覆盖也不落库（避免脱敏回显后被清空）；
     * - timeoutMs 非 null 且 > 0 才更新。
     * 每个被更新的字段都 upsert 进 app_setting 表。
     */
    public synchronized void update(String provider, String baseUrl, String apiKey, String modelName, Integer timeoutMs) {
        // 配置门（P1）：LOCAL_ONLY 模式下拒绝设置云端 provider 或非回环 baseUrl。
        assertPrivacyModeAllows(provider, baseUrl);

        if (StringUtils.hasText(provider)) {
            this.provider = provider.trim();
            persist(KEY_PROVIDER, this.provider);
        }
        if (baseUrl != null) {
            this.baseUrl = baseUrl.trim();
            persist(KEY_BASE_URL, this.baseUrl);
        }
        if (StringUtils.hasText(apiKey)) {
            this.apiKey = apiKey.trim();
            persist(KEY_API_KEY, this.apiKey);
        }
        if (StringUtils.hasText(modelName)) {
            this.modelName = modelName.trim();
            persist(KEY_MODEL_NAME, this.modelName);
        }
        if (timeoutMs != null && timeoutMs > 0) {
            this.timeoutMs = timeoutMs;
            persist(KEY_TIMEOUT_MS, String.valueOf(this.timeoutMs));
        }
    }

    /**
     * 隐私配置门（P1）：LOCAL_ONLY 模式下拒绝配置云端 provider（deepseek/openai）
     * 或非回环 baseUrl，防止误配置导致代码出网。
     *
     * <p>检查失败时抛出 BizException(FORBIDDEN)，调用方（SettingsController）直接返回 403。
     * 失败安全：读取 app_setting 失败时降级放行（不阻断合法配置）。
     */
    private void assertPrivacyModeAllows(String provider, String baseUrl) {
        try {
            AppSettingEntity modeEntity = appSettingMapper.selectById("privacy.mode");
            if (modeEntity == null || !EgressLogEntity.MODE_LOCAL_ONLY.equals(modeEntity.getV())) {
                return; // 非 LOCAL_ONLY，不限制
            }
            // LOCAL_ONLY 模式下的检查
            if (StringUtils.hasText(provider)) {
                String p = provider.trim().toLowerCase(Locale.ROOT);
                if (isCloudProvider(p)) {
                    throw new BizException(ErrorCode.FORBIDDEN,
                            "LOCAL_ONLY 模式下不允许设置云端 provider（" + provider + "）。"
                            + "请先切换到 OPEN 或 ALLOWLIST 模式，或使用本地 Ollama。");
                }
            }
            if (StringUtils.hasText(baseUrl) && !isLoopbackBaseUrl(baseUrl.trim())) {
                throw new BizException(ErrorCode.FORBIDDEN,
                        "LOCAL_ONLY 模式下 baseUrl 必须指向本机（localhost/127.x/::1），"
                        + "当前值 " + baseUrl + " 将导致出网。");
            }
        } catch (BizException biz) {
            throw biz; // 直接上抛业务异常
        } catch (Exception ex) {
            // 读取配置失败时失败安全放行（不阻断合法配置操作）
            log.warn("assertPrivacyModeAllows: read app_setting failed (fail-safe, allowing), err={}", ex.getMessage());
        }
    }

    /**
     * 判断 provider 是否为已知云端服务。
     * 大小写不敏感，精确前缀匹配常见云端 provider 名称。
     */
    public static boolean isCloudProvider(String normalizedProvider) {
        if (!StringUtils.hasText(normalizedProvider)) {
            return false;
        }
        return normalizedProvider.startsWith("deepseek")
                || normalizedProvider.startsWith("openai")
                || normalizedProvider.startsWith("anthropic")
                || normalizedProvider.startsWith("gemini")
                || normalizedProvider.startsWith("cohere")
                || normalizedProvider.startsWith("mistral");
    }

    /**
     * 判断 baseUrl 是否指向本机（回环地址）。
     * 解析 URL 的 host 并检查是否为 loopback；解析失败视为非本机。
     */
    public static boolean isLoopbackBaseUrl(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            if (!StringUtils.hasText(host)) {
                return false;
            }
            // 快速字符串判断常见本机主机名
            String lower = host.toLowerCase(Locale.ROOT);
            if ("localhost".equals(lower) || lower.startsWith("127.") || "::1".equals(lower)) {
                return true;
            }
            // DNS 解析后判断
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for (InetAddress addr : addrs) {
                if (!addr.isLoopbackAddress()) {
                    return false;
                }
            }
            return addrs.length > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** upsert 一条 app_setting：已存在则 updateById，否则 insert。updated_at 交给数据库维护。 */
    private void persist(String key, String value) {
        AppSettingEntity entity = new AppSettingEntity();
        entity.setK(key);
        entity.setV(value);
        if (appSettingMapper.selectById(key) != null) {
            appSettingMapper.updateById(entity);
        } else {
            appSettingMapper.insert(entity);
        }
    }

    private Integer parseTimeout(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
