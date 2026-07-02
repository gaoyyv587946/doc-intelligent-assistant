package com.example.apiagent.controller;

import com.example.apiagent.model.User.UserInfo;
import com.example.apiagent.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户管理接口（仅ADMIN可访问）
 *
 * POST   /api/users                - 创建用户
 * GET    /api/users                - 列出所有用户
 * GET    /api/users/{id}           - 获取单个用户
 * PUT    /api/users/{id}/password  - 修改密码
 * PUT    /api/users/{id}/admin     - 修改权限
 * DELETE /api/users/{id}           - 删除用户
 * GET    /api/users/online         - 获取在线状态
 * POST   /api/users/{username}/force-offline - 强制下线
 */
@RestController
@RequestMapping("/api/users")
public class UserManagementController {

    private static final Logger log = LoggerFactory.getLogger(UserManagementController.class);

    private final UserService userService;

    public UserManagementController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> request) {
        String username = (String) request.get("username");
        String password = (String) request.get("password");
        Boolean admin = (Boolean) request.getOrDefault("admin", false);

        if (username == null || username.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "用户名不能为空"));
        }
        if (password == null || password.length() < 3) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "密码至少3个字符"));
        }

        try {
            UserInfo user = userService.createUser(username, password, admin);
            return ResponseEntity.ok(Map.of("success", true, "user", user, "message", "用户创建成功"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            log.error("创建用户失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "创建失败"));
        }
    }

    @GetMapping
    public ResponseEntity<?> listUsers() {
        try {
            List<UserInfo> users = userService.listUsers();
            // 附加在线状态
            Map<String, Boolean> onlineStatus = userService.getOnlineStatus();
            List<Map<String, Object>> userList = users.stream().map(u -> Map.<String, Object>of(
                    "id", u.id(),
                    "username", u.username(),
                    "admin", u.admin(),
                    "createdAt", u.createdAt().toString(),
                    "updatedAt", u.updatedAt().toString(),
                    "online", onlineStatus.getOrDefault(u.username(), false)
            )).toList();
            return ResponseEntity.ok(Map.of("success", true, "users", userList));
        } catch (Exception e) {
            log.error("获取用户列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "获取失败"));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getUser(@PathVariable long id) {
        try {
            Optional<UserInfo> user = userService.getUser(id);
            if (user.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            UserInfo u = user.get();
            return ResponseEntity.ok(Map.of("success", true, "user", Map.of(
                    "id", u.id(),
                    "username", u.username(),
                    "admin", u.admin(),
                    "createdAt", u.createdAt().toString(),
                    "updatedAt", u.updatedAt().toString(),
                    "online", userService.isOnline(u.username())
            )));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/{id}/password")
    public ResponseEntity<?> updatePassword(@PathVariable long id, @RequestBody Map<String, String> request) {
        String password = request.get("password");
        if (password == null || password.length() < 3) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "密码至少3个字符"));
        }
        try {
            UserInfo user = userService.updatePassword(id, password);
            return ResponseEntity.ok(Map.of("success", true, "user", user, "message", "密码已更新，用户需重新登录"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "更新失败"));
        }
    }

    @PutMapping("/{id}/admin")
    public ResponseEntity<?> updateAdmin(@PathVariable long id, @RequestBody Map<String, Object> request) {
        Boolean admin = (Boolean) request.get("admin");
        if (admin == null) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "admin字段不能为空"));
        }
        try {
            UserInfo user = userService.updateAdmin(id, admin);
            return ResponseEntity.ok(Map.of("success", true, "user", user, "message", "权限已更新"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "更新失败"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable long id) {
        try {
            boolean deleted = userService.deleteUser(id);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("success", true, "message", "用户已删除"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "message", "删除失败"));
        }
    }

    @GetMapping("/online")
    public ResponseEntity<?> getOnlineStatus() {
        return ResponseEntity.ok(Map.of("success", true, "online", userService.getOnlineStatus()));
    }

    @PostMapping("/{username}/force-offline")
    public ResponseEntity<?> forceOffline(@PathVariable String username) {
        userService.forceOffline(username);
        return ResponseEntity.ok(Map.of("success", true, "message", "已强制 " + username + " 下线"));
    }

    @PostMapping("/{username}/allow-online")
    public ResponseEntity<?> allowOnline(@PathVariable String username) {
        userService.clearForceOffline(username);
        return ResponseEntity.ok(Map.of("success", true, "message", "已恢复 " + username + " 上线权限"));
    }
}
