package com.repolens.service;

import com.repolens.domain.dto.auth.ChangePasswordRequest;
import com.repolens.domain.dto.auth.LoginRequest;
import com.repolens.domain.dto.auth.RegisterRequest;
import com.repolens.domain.vo.auth.LoginVO;
import com.repolens.domain.vo.auth.UserInfoVO;

public interface AuthService {
    LoginVO login(LoginRequest request);
    LoginVO register(RegisterRequest request);
    UserInfoVO me(Long userId);
    void changePassword(Long userId, ChangePasswordRequest request);
}
