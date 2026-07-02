package com.example.apiagent.controller;

import com.example.apiagent.model.User;
import com.example.apiagent.security.JwtUtil;
import com.example.apiagent.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 认证接口
 * 基于SQLite数据库的用户登录
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final JwtUtil jwtUtil;
    private final UserService userService;

    public AuthController(JwtUtil jwtUtil, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> loginRequest) {
        String username = loginRequest.get("username");
        String password = loginRequest.get("password");

        if (username == null || password == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名和密码不能为空"));
        }

        try {
            Optional<User> userOpt = userService.authenticate(username, password);
            if (userOpt.isEmpty()) {
                return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
            }

            User user = userOpt.get();
            String token = jwtUtil.generateToken(user.username(), user.admin());
            log.info("用户登录: {} (admin={})", user.username(), user.admin());

            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "username", user.username(),
                    "admin", user.admin(),
                    "message", "登录成功"
            ));
        } catch (Exception e) {
            log.error("登录异常: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "登录服务异常"));
        }
    }

    @GetMapping("/validate")
    public ResponseEntity<?> validate(@RequestHeader("Authorization") String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(401).body(Map.of("error", "Token无效"));
        }

        String token = authHeader.substring(7);
        if (jwtUtil.validateToken(token)) {
            String username = jwtUtil.getUsernameFromToken(token);
            boolean isAdmin = jwtUtil.isAdmin(token);
            return ResponseEntity.ok(Map.of(
                    "username", username,
                    "admin", isAdmin,
                    "valid", true));
        }

        return ResponseEntity.status(401).body(Map.of("error", "Token已过期或无效"));
    }
}
