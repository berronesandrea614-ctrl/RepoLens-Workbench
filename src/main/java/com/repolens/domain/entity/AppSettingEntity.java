package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用运行时配置条目：key/value 单表，用于持久化可在应用内改动的设置
 * （如 LLM provider/base-url/api-key/model-name/timeout）。
 * updated_at 由数据库 ON UPDATE CURRENT_TIMESTAMP 维护，实体不写入。
 */
@Data
@TableName("app_setting")
public class AppSettingEntity {

    /** 配置键，主键（非自增，由业务显式指定）。 */
    @TableId(value = "k", type = IdType.INPUT)
    private String k;

    /** 配置值，可为空。 */
    private String v;

    /** 最后更新时间，由数据库维护。 */
    private LocalDateTime updatedAt;
}
