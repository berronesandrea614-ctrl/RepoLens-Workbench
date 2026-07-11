package com.repolens.service.impl;

import com.repolens.domain.entity.UserEntity;
import com.repolens.mapper.UserMapper;
import com.repolens.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    @Override
    public UserEntity getById(Long userId) {
        return userMapper.selectById(userId);
    }

    @Override
    public boolean exists(Long userId) {
        return getById(userId) != null;
    }
}
