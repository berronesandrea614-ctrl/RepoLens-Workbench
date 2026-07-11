package com.repolens.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.repolens.domain.entity.UserAccountEntity;
import com.repolens.domain.entity.WorkspaceMemberEntity;
import com.repolens.domain.enums.WorkspaceRole;
import com.repolens.mapper.UserAccountMapper;
import com.repolens.mapper.WorkspaceMemberMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserAccountSeeder implements ApplicationRunner {

    private static final Long ADMIN_ID = 1L;
    private static final Long ADMIN_WORKSPACE_ID = 1L;
    private static final String ADMIN_USERNAME = "admin";
    private static final String INITIAL_PASSWORD = "repolens@2026";

    private final UserAccountMapper userAccountMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        long count = userAccountMapper.selectCount(null);
        if (count == 0) {
            UserAccountEntity admin = new UserAccountEntity();
            admin.setId(ADMIN_ID);
            admin.setUsername(ADMIN_USERNAME);
            admin.setPasswordHash(passwordEncoder.encode(INITIAL_PASSWORD));
            admin.setDisplayName("管理员");
            admin.setCreatedAt(LocalDateTime.now());
            userAccountMapper.insert(admin);
            log.info("[Auth] 初始管理员账号已创建（username=admin），请首次登录后及时修改密码。");
        }
        // Idempotent: ensure admin has an OWNER membership in workspace 1.
        // In a fresh DB the seed data already contains this row; this guard
        // prevents a duplicate-key error on re-run while also auto-healing a
        // missing row if the seed data was somehow incomplete.
        ensureAdminWorkspaceMembership();
    }

    private void ensureAdminWorkspaceMembership() {
        boolean exists = workspaceMemberMapper.selectCount(
                Wrappers.<WorkspaceMemberEntity>lambdaQuery()
                        .eq(WorkspaceMemberEntity::getWorkspaceId, ADMIN_WORKSPACE_ID)
                        .eq(WorkspaceMemberEntity::getUserId, ADMIN_ID)) > 0;
        if (!exists) {
            WorkspaceMemberEntity member = new WorkspaceMemberEntity();
            member.setWorkspaceId(ADMIN_WORKSPACE_ID);
            member.setUserId(ADMIN_ID);
            member.setRole(WorkspaceRole.OWNER);
            workspaceMemberMapper.insert(member);
            log.info("[Auth] 管理员工作区成员记录已补全（workspaceId=1, userId=1, role=OWNER）。");
        }
    }
}
