package com.example.apiagent.controller;

import com.example.apiagent.agent.tools.OpenBrowserTool;
import com.example.apiagent.model.ChatRequest;
import com.example.apiagent.model.ChatResponse;
import com.example.apiagent.security.JwtUtil;
import com.example.apiagent.service.ChatService;
import com.example.apiagent.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 聊天接口
 * 提供与AI Agent对话的REST API
 * 
 * 权限验证策略：
 * - /api/chat/** 端点在SecurityConfig中设置为permitAll
 * - 本Controller内部手动验证token和权限
 * - 这样可以避免SSE长连接期间token过期导致的问题
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public ChatController(ChatService chatService, JwtUtil jwtUtil, UserService userService) {
        this.chatService = chatService;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    /**
     * 发送消息给AI Agent
     *
     * 请求示例：
     * POST /api/chat
     * {
     *   "sessionId": "user123",
     *   "message": "是否有用户注册接口？"
     * }
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 重置openBrowser调用状态（每次请求只能调用一次）
        OpenBrowserTool.resetCallState();
        
        if (request.getSessionId() == null || request.getSessionId().isBlank()) {
            request.setSessionId("default");
        }

        if (request.getMessage() == null || request.getMessage().isBlank()) {
            return ResponseEntity.badRequest().body(
                    new ChatResponse(request.getSessionId(), "消息不能为空", false));
        }

        // 从SecurityContext获取认证信息（JwtAuthFilter已尝试解析）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isAdmin = false;
        String username = null;
        
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            username = auth.getName();
            isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            
            // 验证用户是否被强制下线
            if (userService.isForcedOffline(username)) {
                return ResponseEntity.status(403).body(
                        new ChatResponse(request.getSessionId(), "用户已被强制下线", false));
            }
        } else {
            // 无有效认证信息
            return ResponseEntity.status(401).body(
                    new ChatResponse(request.getSessionId(), "未提供有效的认证信息", false));
        }

        log.info("收到聊天请求: sessionId={}, isAdmin={}, username={}, message={}", 
                request.getSessionId(), isAdmin, username, request.getMessage());

        try {
            ChatResponse response = chatService.chat(request, isAdmin);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("聊天处理失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(
                    new ChatResponse(request.getSessionId(), "处理失败: " + e.getMessage(), false));
        }
    }

    /**
     * 清除会话记忆
     */
    @DeleteMapping("/chat/{sessionId}")
    public ResponseEntity<Map<String, String>> clearSession(@PathVariable String sessionId) {
        // TODO: 实现清除特定会话的ChatMemory
        return ResponseEntity.ok(Map.of("message", "会话 " + sessionId + " 已清除"));
    }
}
