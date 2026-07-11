package com.repolens.domain.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {
    @NotBlank
    @Size(min = 3, max = 64, message = "用户名长度须在 3-64 个字符之间")
    private String username;

    @NotBlank
    @Size(min = 6, message = "密码长度不能少于 6 位")
    private String password;

    /** Optional display name; defaults to username when omitted. */
    private String displayName;
}
