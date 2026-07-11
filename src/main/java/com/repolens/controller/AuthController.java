package com.repolens.controller;

import com.repolens.common.result.Result;
import com.repolens.domain.dto.auth.ChangePasswordRequest;
import com.repolens.domain.dto.auth.LoginRequest;
import com.repolens.domain.dto.auth.RegisterRequest;
import com.repolens.domain.vo.auth.LoginVO;
import com.repolens.domain.vo.auth.UserInfoVO;
import com.repolens.security.AuthUserId;
import com.repolens.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/register")
    public Result<LoginVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @GetMapping("/me")
    public Result<UserInfoVO> me(@AuthUserId Long userId) {
        return Result.success(authService.me(userId));
    }

    @PostMapping("/password")
    public Result<Void> changePassword(@AuthUserId Long userId,
                                        @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(userId, request);
        return Result.success(null);
    }
}
