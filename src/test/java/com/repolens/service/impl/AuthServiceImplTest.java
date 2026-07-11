package com.repolens.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.domain.dto.auth.LoginRequest;
import com.repolens.domain.dto.auth.RegisterRequest;
import com.repolens.domain.entity.UserAccountEntity;
import com.repolens.domain.entity.WorkspaceEntity;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.domain.vo.auth.LoginVO;
import com.repolens.mapper.UserAccountMapper;
import com.repolens.mapper.WorkspaceMapper;
import com.repolens.security.JwtService;
import com.repolens.service.WorkspaceMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    UserAccountMapper userAccountMapper;

    @Mock
    WorkspaceMapper workspaceMapper;

    @Mock
    WorkspaceMemberService workspaceMemberService;

    @Mock
    JwtService jwtService;

    @Mock
    PasswordEncoder passwordEncoder;

    @InjectMocks
    AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$dummyfakehashdummy");
        authService.initDummyHash();
    }

    @Test
    void login_unknownUser_throwsSameMessageAsWrongPassword() {
        // user not found
        when(userAccountMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        // BCrypt compare will return false (dummy hash won't match)
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("anyPassword");

        BizException ex = assertThrows(BizException.class, () -> authService.login(request));
        assertEquals("用户名或密码错误", ex.getMessage());
        // Critical: matches() MUST have been called even though the user doesn't exist
        verify(passwordEncoder).matches(any(), any());
    }

    @Test
    void login_wrongPassword_throwsException() {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(1L);
        user.setUsername("alice");
        user.setPasswordHash("$2a$10$realhashere");

        when(userAccountMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches(any(), eq("$2a$10$realhashere"))).thenReturn(false);

        LoginRequest request = new LoginRequest();
        request.setUsername("alice");
        request.setPassword("wrongpass");

        BizException ex = assertThrows(BizException.class, () -> authService.login(request));
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void login_success_returnsLoginVO() {
        UserAccountEntity user = new UserAccountEntity();
        user.setId(42L);
        user.setUsername("bob");
        user.setPasswordHash("$2a$10$correcthash");

        when(userAccountMapper.selectOne(any(QueryWrapper.class))).thenReturn(user);
        when(passwordEncoder.matches(any(), eq("$2a$10$correcthash"))).thenReturn(true);
        when(jwtService.generateToken(42L, "bob")).thenReturn("tok");

        LoginRequest request = new LoginRequest();
        request.setUsername("bob");
        request.setPassword("correctpass");

        LoginVO vo = authService.login(request);

        assertNotNull(vo);
        assertEquals("tok", vo.getToken());
        assertEquals(42L, vo.getUserId());
        assertEquals("bob", vo.getUsername());
    }

    // ── register tests ──────────────────────────────────────────────────────

    @Test
    void register_success_returnsLoginVOWithDistinctId() {
        // No existing user with that username
        when(userAccountMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        // Current max id is 1 (admin seed); new user gets 2
        when(userAccountMapper.selectMaxId()).thenReturn(1L);
        when(userAccountMapper.insert(any(UserAccountEntity.class))).thenReturn(1);
        when(passwordEncoder.encode("secret1")).thenReturn("$2a$hash");
        when(jwtService.generateToken(2L, "newuser")).thenReturn("new.tok");

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("secret1");

        LoginVO vo = authService.register(req);

        assertNotNull(vo);
        assertEquals("new.tok", vo.getToken());
        assertEquals(2L, vo.getUserId());
        assertEquals("newuser", vo.getUsername());
        // ensure id != admin seed (1)
        assertNotEquals(1L, vo.getUserId());
    }

    @Test
    void register_duplicateUsername_throws409() {
        when(userAccountMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("admin");
        req.setPassword("anypass1");

        BizException ex = assertThrows(BizException.class, () -> authService.register(req));
        assertEquals("用户名已存在", ex.getMessage());
        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    @Test
    void register_canLoginAfterRegistration() {
        // Register creates user with id=2
        when(userAccountMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(userAccountMapper.selectMaxId()).thenReturn(1L);
        when(userAccountMapper.insert(any(UserAccountEntity.class))).thenReturn(1);
        when(passwordEncoder.encode("mypassword")).thenReturn("$2a$myhash");
        when(jwtService.generateToken(2L, "charlie")).thenReturn("charlie.tok");

        RegisterRequest req = new RegisterRequest();
        req.setUsername("charlie");
        req.setPassword("mypassword");
        req.setDisplayName("Charlie Brown");

        LoginVO vo = authService.register(req);
        assertEquals(2L, vo.getUserId());
        assertEquals("charlie", vo.getUsername());

        // Simulate subsequent login with the same user
        UserAccountEntity saved = new UserAccountEntity();
        saved.setId(2L);
        saved.setUsername("charlie");
        saved.setPasswordHash("$2a$myhash");

        when(userAccountMapper.selectOne(any(QueryWrapper.class))).thenReturn(saved);
        when(passwordEncoder.matches("mypassword", "$2a$myhash")).thenReturn(true);
        when(jwtService.generateToken(2L, "charlie")).thenReturn("login.charlie.tok");

        LoginRequest loginReq = new LoginRequest();
        loginReq.setUsername("charlie");
        loginReq.setPassword("mypassword");
        LoginVO loginVo = authService.login(loginReq);
        assertEquals(2L, loginVo.getUserId());
    }

    @Test
    void register_createsPersonalWorkspaceAndOwnerMembership() {
        // Arrange
        when(userAccountMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(userAccountMapper.selectMaxId()).thenReturn(1L);
        when(userAccountMapper.insert(any(UserAccountEntity.class))).thenReturn(1);
        when(passwordEncoder.encode("secret1")).thenReturn("$2a$hash");
        when(jwtService.generateToken(2L, "newuser")).thenReturn("new.tok");

        // Simulate auto-generated workspace id from DB
        when(workspaceMapper.insert(any(WorkspaceEntity.class))).thenAnswer(invocation -> {
            WorkspaceEntity ws = invocation.getArgument(0);
            ws.setId(99L);
            return 1;
        });
        when(workspaceMemberService.addMember(eq(99L), eq(2L), eq(WorkspaceRole.OWNER)))
                .thenReturn(new WorkspaceMemberEntity());

        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("secret1");

        // Act
        LoginVO vo = authService.register(req);

        // Assert token / user id are correct
        assertEquals(2L, vo.getUserId());
        assertEquals("newuser", vo.getUsername());

        // The workspace must be owned by the new user and named after them.
        // Use ArgumentCaptor to avoid ambiguous insert(T) vs insert(Collection<T>) overloads.
        ArgumentCaptor<WorkspaceEntity> wsCaptor = ArgumentCaptor.forClass(WorkspaceEntity.class);
        verify(workspaceMapper).insert(wsCaptor.capture());
        WorkspaceEntity capturedWs = wsCaptor.getValue();
        assertEquals(2L, capturedWs.getOwnerId().longValue());
        assertNotNull(capturedWs.getName());
        assertTrue(capturedWs.getName().contains("newuser"));

        // The new user must be added as OWNER of their personal workspace
        verify(workspaceMemberService).addMember(99L, 2L, WorkspaceRole.OWNER);
    }
}
