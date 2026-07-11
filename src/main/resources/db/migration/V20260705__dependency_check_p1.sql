-- D-P1 Migration: dependency_check new column + registry cache table
-- MySQL 8 plain ALTER (no IF NOT EXISTS per project convention)

ALTER TABLE dependency_check ADD COLUMN checked_offline TINYINT(1) DEFAULT 0;

CREATE TABLE dependency_registry_cache (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    ecosystem VARCHAR(20) NOT NULL,
    package_name VARCHAR(255) NOT NULL,
    exists_flag TINYINT(1) DEFAULT NULL,
    malicious_ids VARCHAR(500) DEFAULT NULL,
    vulnerable_ids VARCHAR(500) DEFAULT NULL,
    checked_at DATETIME NOT NULL,
    UNIQUE KEY uq_dep_cache (ecosystem, package_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
