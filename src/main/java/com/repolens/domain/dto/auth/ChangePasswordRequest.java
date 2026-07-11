package com.repolens.domain.dto.auth;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank private String oldPassword;
    @NotBlank private String newPassword;
}
