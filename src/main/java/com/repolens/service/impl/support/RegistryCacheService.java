package com.repolens.service.impl.support;

import com.repolens.config.DependencyCheckProperties;
import com.repolens.domain.entity.DependencyRegistryCacheEntity;
import com.repolens.mapper.DependencyRegistryCacheMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Registry 存在性 + OSV 结果的写透（write-through）TTL 缓存。
 * <p>
 * 策略：先查 {@code dependency_registry_cache} 表；
 * 若命中且未过期（{@code checkedAt + cacheTtlDays > now}）直接返回，否则缓存穿透，
 * 调用方拿到真实结果后调用 {@link #put} 写回。
 * </p>
 * <p>
 * 所有 DB 异常均被捕获静默处理，永不向调用方抛出——缓存层不阻断体检流程。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryCacheService {

    private final DependencyRegistryCacheMapper cacheMapper;
    private final DependencyCheckProperties properties;

    /**
     * 查询缓存。
     *
     * @param ecosystem   npm / pypi / maven
     * @param packageName 包名
     * @return 命中且未过期返回 Optional.of(entity)；否则 Optional.empty()。
     */
    public Optional<DependencyRegistryCacheEntity> get(String ecosystem, String packageName) {
        try {
            Optional<DependencyRegistryCacheEntity> opt = cacheMapper.findByKey(ecosystem, packageName);
            if (opt.isEmpty()) return Optional.empty();
            LocalDateTime expiry = opt.get().getCheckedAt().plusDays(properties.getCacheTtlDays());
            if (LocalDateTime.now().isAfter(expiry)) {
                log.debug("Cache TTL expired for {}:{}", ecosystem, packageName);
                return Optional.empty();
            }
            return opt;
        } catch (Exception ex) {
            log.debug("Cache get failed {}:{}: {}", ecosystem, packageName, ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 写透：先删再插（模拟 upsert，兼容 MySQL 的 UNIQUE 约束）。
     *
     * @param ecosystem    npm / pypi / maven
     * @param packageName  包名
     * @param existsFlag   1=存在，0=不存在，null=未知
     * @param maliciousIds MAL-* ids，null 表示无
     * @param vulnerableIds CVE-/GHSA-* ids，null 表示无
     */
    public void put(String ecosystem, String packageName,
                    Boolean existsFlag, String maliciousIds, String vulnerableIds) {
        try {
            cacheMapper.deleteByKey(ecosystem, packageName);
            DependencyRegistryCacheEntity entry = new DependencyRegistryCacheEntity();
            entry.setEcosystem(ecosystem);
            entry.setPackageName(packageName);
            entry.setExistsFlag(existsFlag);
            entry.setMaliciousIds(maliciousIds);
            entry.setVulnerableIds(vulnerableIds);
            entry.setCheckedAt(LocalDateTime.now());
            cacheMapper.insert(entry);
        } catch (Exception ex) {
            log.debug("Cache put failed {}:{}: {}", ecosystem, packageName, ex.getMessage());
        }
    }
}
