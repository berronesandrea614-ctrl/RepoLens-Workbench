package com.repolens.service;

import com.repolens.domain.entity.UserEntity;

public interface UserService {

    UserEntity getById(Long userId);

    boolean exists(Long userId);
}
