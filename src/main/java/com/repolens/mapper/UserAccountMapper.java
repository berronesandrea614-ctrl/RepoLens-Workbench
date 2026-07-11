package com.repolens.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.UserAccountEntity;
import org.apache.ibatis.annotations.Select;

public interface UserAccountMapper extends BaseMapper<UserAccountEntity> {
    /** Returns the current maximum user id, or 1 when the table has only the seed row. */
    @Select("SELECT COALESCE(MAX(id), 1) FROM user_account")
    Long selectMaxId();
}
