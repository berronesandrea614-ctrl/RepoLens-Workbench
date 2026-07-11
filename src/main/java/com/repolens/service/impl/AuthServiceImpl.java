package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.auth.ChangePasswordRequest;
import com.repolens.domain.dto.auth.LoginRequest;
import com.repolens.domain.dto.auth.RegisterRequest;
import com.repolens.domain.entity.UserAccountEntity;
import com.repolens.domain.entity.WorkspaceEntity;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.domain.vo.auth.LoginVO;
import com.repolens.domain.vo.auth.UserInfoVO;
import com.repolens.mapper.UserAccountMapper;
import com.repolens.mapper.WorkspaceMapper;
import com.repolens.security.JwtService;
import com.repolens.service.AuthService;
import com.repolens.service.WorkspaceMemberService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserAccountMapper userAccountMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberService workspaceMemberService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode("__dummy_sentinel_" + UUID.randomUUID());
    }

    @Override
    public LoginVO login(LoginRequest request) {
        UserAccountEntity user = userAccountMapper.selectOne(
                new QueryWrapper<UserAccountEntity>().eq("username", request.getUsername()));
        boolean passwordMatches = passwordEncoder.matches(
                request.getPassword(), user != null ? user.getPasswordHash() : dummyHash);
        if (user == null || !passwordMatches) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        }
        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginVO register(RegisterRequest request) {
        // Duplicate username check
        long existing = userAccountMapper.selectCount(
                new QueryWrapper<UserAccountEntity>().eq("username", request.getUsername()));
        if (existing > 0) {
            throw new BizException(ErrorCode.CONFLICT, "用户名已存在");
        }
        // Assign next id safely: MAX(id)+1 ensures no collision with seed (id=1)
        long newId = userAccountMapper.selectMaxId() + 1;

        UserAccountEntity user = new UserAccountEntity();
        user.setId(newId);
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setDisplayName(StringUtils.hasText(request.getDisplayName())
                ? request.getDisplayName() : request.getUsername());
        user.setCreatedAt(LocalDateTime.now());
        userAccountMapper.insert(user);

        // Create a personal workspace for the new user so they can immediately create repos.
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setName(user.getDisplayName() + " 的工作区");
        workspace.setOwnerId(newId);
        workspaceMapper.insert(workspace);

        // Add the new user as OWNER of their personal workspace (idempotent).
        workspaceMemberService.addMember(workspace.getId(), newId, WorkspaceRole.OWNER);
        log.info("[Auth] 新用户 {} 已创建个人工作区 id={}", user.getUsername(), workspace.getId());

        String token = jwtService.generateToken(user.getId(), user.getUsername());
        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    @Override
    public UserInfoVO me(Long userId) {
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return UserInfoVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .displayName(user.getDisplayName())
                .build();
    }

    @Override
    public void changePassword(Long userId, ChangePasswordRequest request) {
        UserAccountEntity user = userAccountMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "旧密码不正确");
        }
        if (request.getNewPassword().length() < 6) {
            throw new BizException(ErrorCode.BAD_REQUEST, "新密码长度不能少于 6 位");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        userAccountMapper.updateById(user);
    }
}
