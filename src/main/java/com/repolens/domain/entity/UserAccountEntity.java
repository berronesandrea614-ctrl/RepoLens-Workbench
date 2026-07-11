package com.repolens.domain.entity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("user_account")
public class UserAccountEntity {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private LocalDateTime createdAt;
}
