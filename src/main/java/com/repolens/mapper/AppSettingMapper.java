package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.AppSettingEntity;

/**
 * app_setting 单表读写。key/value 结构，仅需 BaseMapper 的 selectList/selectById/insert/updateById。
 */
public interface AppSettingMapper extends BaseMapper<AppSettingEntity> {
}
