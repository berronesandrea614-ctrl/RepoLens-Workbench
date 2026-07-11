package com.repolens.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * L3 session memory：L1 compaction 时把被清理的 tool_result 要点追加到此表，
 * 后续作为 CONTEXT SUMMARY 消息注入到历史开头。
 */
@Data
@TableName("session_context_note")
public class SessionContextNoteEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String noteText;

    private LocalDateTime createdAt;
}
