package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.DependencyRegistryCacheEntity;

import java.util.Optional;

/**
 * dependency_registry_cache 表 Mapper。
 * 提供按 (ecosystem, package_name) 唯一键查询与删除的便捷方法。
 */
public interface DependencyRegistryCacheMapper extends BaseMapper<DependencyRegistryCacheEntity> {

    /**
     * 按 (ecosystem, package_name) 查询缓存条目。
     */
    default Optional<DependencyRegistryCacheEntity> findByKey(String ecosystem, String packageName) {
        return Optional.ofNullable(selectOne(
                Wrappers.<DependencyRegistryCacheEntity>lambdaQuery()
                        .eq(DependencyRegistryCacheEntity::getEcosystem, ecosystem)
                        .eq(DependencyRegistryCacheEntity::getPackageName, packageName)));
    }

    /**
     * 删除指定 (ecosystem, package_name) 的缓存条目（更新前调用，模拟 upsert）。
     */
    default void deleteByKey(String ecosystem, String packageName) {
        delete(Wrappers.<DependencyRegistryCacheEntity>lambdaQuery()
                .eq(DependencyRegistryCacheEntity::getEcosystem, ecosystem)
                .eq(DependencyRegistryCacheEntity::getPackageName, packageName));
    }
}
