package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Registry 存在性 + OSV 恶意结果缓存。
 * <p>
 * TTL 由代码管理：{@code checkedAt + cacheTtlDays > now} 时视为命中，否则视为过期。
 * 唯一键：(ecosystem, package_name)——更新时先 deleteByKey 再 insert（避免 MySQL upsert 语法差异）。
 * </p>
 *
 * <p><b>手动建表 DDL（live-DB manual apply）：</b></p>
 * <pre>
 * CREATE TABLE dependency_registry_cache (
 *     id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
 *     ecosystem     VARCHAR(20)  NOT NULL COMMENT 'npm / pypi / maven',
 *     package_name  VARCHAR(255) NOT NULL,
 *     exists_flag   TINYINT(1)   DEFAULT NULL COMMENT '1=exists 0=404 NULL=unchecked',
 *     malicious_ids VARCHAR(500) DEFAULT NULL COMMENT 'MAL-* OSV ids comma-separated',
 *     vulnerable_ids VARCHAR(500) DEFAULT NULL COMMENT 'CVE/GHSA ids comma-separated',
 *     checked_at    DATETIME     NOT NULL,
 *     UNIQUE KEY uq_dep_cache (ecosystem, package_name)
 * ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
 * </pre>
 */
@Data
@TableName("dependency_registry_cache")
public class DependencyRegistryCacheEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 生态系统：npm / pypi / maven。 */
    private String ecosystem;

    private String packageName;

    /** 1=存在，0=registry 404，null=未检测到存在性。 */
    private Boolean existsFlag;

    /** 逗号分隔的 MAL-* OSV id，null 表示未检测到恶意。 */
    private String maliciousIds;

    /** 逗号分隔的 CVE-/GHSA-* id，null 表示未检测到漏洞。 */
    private String vulnerableIds;

    /** 缓存写入时间，用于 TTL 判断。 */
    private LocalDateTime checkedAt;
}
