package com.repolens.domain.vo.auth;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LoginVO {
    private String token;
    private Long userId;
    private String username;
}
