package com.repolens.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repolens.domain.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE session_id = #{sessionId}")
    Long selectMaxIdBySession(@Param("sessionId") Long sessionId);

    @Update("UPDATE chat_message SET rewound = 1 WHERE session_id = #{sessionId} AND id > #{lastMessageId}")
    int softDeleteAfter(@Param("sessionId") Long sessionId, @Param("lastMessageId") Long lastMessageId);
}
