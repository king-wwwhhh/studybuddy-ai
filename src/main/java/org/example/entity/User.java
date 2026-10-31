package org.example.entity;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 系统用户（角色：USER / ADMIN） */
@Setter
@Getter
public class User {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    private Long id;
    private String email;
    /** 登录名（唯一，可为空；为空时只能用邮箱登录） */
    private String username;
    /** BCrypt 哈希；验证码注册的用户初始为空 */
    private String passwordHash;
    private String nickname;
    private String role;
    /** 账号状态：ACTIVE / DISABLED */
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastLoginAt;

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public boolean isActive() {
        return status == null || "ACTIVE".equals(status);
    }
}
