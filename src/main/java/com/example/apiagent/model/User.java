package com.example.apiagent.model;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
public record User(
        Long id,
        String username,
        String password,   // BCrypt加密
        boolean admin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /** 不含密码的安全版本，用于API返回 */
    public UserInfo toInfo() {
        return new UserInfo(id, username, admin, createdAt, updatedAt);
    }

    public record UserInfo(
            Long id,
            String username,
            boolean admin,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}
}
