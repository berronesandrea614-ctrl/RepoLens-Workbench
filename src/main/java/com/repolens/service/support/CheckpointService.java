package com.repolens.service.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repolens.domain.entity.CheckpointEntity;
import com.repolens.mapper.CheckpointMapper;
import com.repolens.mapper.FileChangeLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckpointService {

    private final CheckpointMapper checkpointMapper;
    private final FileChangeLogMapper fileChangeLogMapper;

    public Long createCheckpoint(Long sessionId, Long parentId, Long highWaterMark,
                                  Long lastMessageId, String label) {
        try {
            CheckpointEntity cp = new CheckpointEntity();
            cp.setSessionId(sessionId);
            cp.setParentId(parentId);
            cp.setHighWaterMark(highWaterMark);
            cp.setLastMessageId(lastMessageId);
            cp.setLabel(label);
            cp.setCreatedAt(LocalDateTime.now());
            checkpointMapper.insert(cp);
            return cp.getId();
        } catch (Exception e) {
            log.warn("CheckpointService.createCheckpoint failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean rewindTo(Long sessionId, Long checkpointId) {
        try {
            CheckpointEntity cp = checkpointMapper.selectById(checkpointId);
            if (cp == null || !cp.getSessionId().equals(sessionId)) {
                return false;
            }
            if (cp.getHighWaterMark() != null) {
                fileChangeLogMapper.deleteBeyondWatermark(sessionId, cp.getHighWaterMark());
            }
            return true;
        } catch (Exception e) {
            log.warn("CheckpointService.rewindTo failed: {}", e.getMessage());
            return false;
        }
    }

    /** 获取 checkpoint 树（按创建时间升序），供前端渲染分支树。 */
    public List<CheckpointEntity> getTree(Long sessionId) {
        return checkpointMapper.selectList(
                new QueryWrapper<CheckpointEntity>()
                        .eq("session_id", sessionId)
                        .orderByAsc("created_at"));
    }
}
