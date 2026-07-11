package com.repolens.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import com.repolens.common.exception.GlobalExceptionHandler;
import com.repolens.domain.dto.auth.ChangePasswordRequest;
import com.repolens.domain.dto.auth.LoginRequest;
import com.repolens.domain.dto.auth.RegisterRequest;
import com.repolens.domain.vo.auth.LoginVO;
import com.repolens.domain.vo.auth.UserInfoVO;
import com.repolens.service.AuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private AuthService authService;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        authService = mock(AuthService.class);
        AuthController controller = new AuthController(authService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(TestAuthUtils.fixedUserIdResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    void login_success_returnsTokenAndUserInfo() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("repolens@2026");

        when(authService.login(any(LoginRequest.class))).thenReturn(
                LoginVO.builder().token("test.jwt.token").userId(1L).username("admin").build());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("test.jwt.token"))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin");
        req.setPassword("wrongpassword");

        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BizException(ErrorCode.UNAUTHORIZED, "用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void me_returnsUserInfo() throws Exception {
        when(authService.me(eq(1L))).thenReturn(
                UserInfoVO.builder().userId(1L).username("admin").displayName("管理员").build());

        mockMvc.perform(get("/api/auth/me").accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.displayName").value("管理员"));
    }

    @Test
    void changePassword_success_returns200() throws Exception {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("repolens@2026");
        req.setNewPassword("newPass123");

        mockMvc.perform(post("/api/auth/password")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void changePassword_wrongOldPassword_returns401() throws Exception {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("wrongold");
        req.setNewPassword("newPass123");

        doThrow(new BizException(ErrorCode.UNAUTHORIZED, "旧密码不正确"))
                .when(authService).changePassword(eq(1L), any(ChangePasswordRequest.class));

        mockMvc.perform(post("/api/auth/password")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    // ── register endpoint tests ──────────────────────────────────────────────

    @Test
    void register_success_returns200WithToken() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("newuser");
        req.setPassword("secret123");

        when(authService.register(any(RegisterRequest.class))).thenReturn(
                LoginVO.builder().token("reg.jwt.token").userId(2L).username("newuser").build());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("reg.jwt.token"))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("admin");
        req.setPassword("somepass1");

        when(authService.register(any(RegisterRequest.class)))
                .thenThrow(new BizException(ErrorCode.CONFLICT, "用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    void register_shortUsername_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("ab"); // too short (< 3)
        req.setPassword("validpass");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        RegisterRequest req = new RegisterRequest();
        req.setUsername("validuser");
        req.setPassword("abc"); // too short (< 6)

        mockMvc.perform(post("/api/auth/register")
                        .contentType(APPLICATION_JSON)
                        .accept(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
