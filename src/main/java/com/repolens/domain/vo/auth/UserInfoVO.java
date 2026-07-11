package com.repolens.domain.vo.auth;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserInfoVO {
    private Long userId;
    private String username;
    private String displayName;
}
