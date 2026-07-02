package com.example.apiagent.controller;

import com.example.apiagent.agent.tools.OpenBrowserTool;
import com.example.apiagent.security.JwtUtil;
import com.example.apiagent.service.StreamingChatService;
import com.example.apiagent.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 流式聊天控制器
 * 提供SSE（Server-Sent Events）接口，实现实时流式输出
 * 
 * 权限验证策略：
 * - /api/chat/** 端点在SecurityConfig中设置为permitAll
 * - 本Controller内部手动验证token和权限
 * - 这样可以避免SSE长连接期间token过期导致的问题
 */
@RestController
@RequestMapping("/api")
public class StreamingChatController {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatController.class);
    
    private static final SecurityContextHolderStrategy securityContextHolderStrategy = 
            SecurityContextHolder.getContextHolderStrategy();
    
    private static final ScheduledExecutorService scheduler = 
            new java.util.concurrent.ScheduledThreadPoolExecutor(2, new CustomizableThreadFactory("token-check-"));

    private final StreamingChatService streamingChatService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    
    private final ConcurrentHashMap<String, StreamSession> streamSessionCache = new ConcurrentHashMap<>();

    public StreamingChatController(StreamingChatService streamingChatService, 
                                   UserService userService, 
                                   JwtUtil jwtUtil) {
        this.streamingChatService = streamingChatService;
        this.userService = userService;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 流式会话信息
     */
    private static class StreamSession {
        final StringBuilder content = new StringBuilder();
        volatile boolean completed = false;
        volatile String error = null;
        volatile String urlToOpen = null;
        volatile long lastUpdateTime = System.currentTimeMillis();
        volatile boolean authExpired = false;
        
        synchronized void appendToken(String token) {
            content.append(token);
            lastUpdateTime = System.currentTimeMillis();
        }
        
        synchronized void complete(String urlToOpen) {
            this.completed = true;
            this.urlToOpen = urlToOpen;
            lastUpdateTime = System.currentTimeMillis();
        }
        
        synchronized void setError(String error) {
            this.error = error;
            this.completed = true;
            lastUpdateTime = System.currentTimeMillis();
        }
        
        synchronized void setAuthExpired() {
            this.authExpired = true;
            this.completed = true;
            lastUpdateTime = System.currentTimeMillis();
        }
        
        synchronized String getContent() {
            return content.toString();
        }
    }

    /**
     * SSE流式聊天接口
     * 
     * 权限验证：
     * 1. JwtAuthFilter会尝试解析token，但不强制验证失败
     * 2. 本方法内部手动验证token和权限
     * 3. 如果验证失败，返回错误SSE事件而不是中断连接
     */
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestParam String sessionId,
            @RequestParam String message,
            @RequestParam(required = false) String token) {

        // 重置openBrowser调用状态
        OpenBrowserTool.resetCallState();

        // 从SecurityContext获取认证信息（JwtAuthFilter已尝试解析）
        SecurityContext securityContext = securityContextHolderStrategy.getContext();
        Authentication auth = securityContext.getAuthentication();
        boolean isAdmin = false;
        String username = null;
        String jwtToken = token;
        
        // 验证认证信息
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            username = auth.getName();
            isAdmin = auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        } else {
            // 无有效认证信息，返回错误SSE事件
            SseEmitter emitter = new SseEmitter(5000L);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("message", "未提供有效的认证信息"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                log.error("发送认证错误事件失败: {}", e.getMessage());
            }
            return emitter;
        }

        final String finalJwtToken = jwtToken;
        final String finalUsername = username;
        final boolean finalIsAdmin = isAdmin;

        log.info("收到SSE流式聊天请求: sessionId={}, isAdmin={}, username={}", sessionId, isAdmin, username);

        StreamSession session = streamSessionCache.computeIfAbsent(sessionId, k -> new StreamSession());
        SseEmitter emitter = new SseEmitter(300_000L);

        // 注册回调
        emitter.onTimeout(() -> {
            log.warn("SSE连接超时: sessionId={}", sessionId);
            streamingChatService.cleanup();
            emitter.complete();
        });

        emitter.onCompletion(() -> {
            log.info("SSE连接完成: sessionId={}", sessionId);
            streamingChatService.cleanup();
        });

        emitter.onError(e -> {
            log.error("SSE连接错误: sessionId={}, error={}", sessionId, e.getMessage());
            streamingChatService.cleanup();
        });

        // 启动token有效性定时检查
        final java.util.concurrent.ScheduledFuture<?>[] tokenCheckTask = new java.util.concurrent.ScheduledFuture<?>[1];
        
        final Authentication finalAuth = auth;
        CompletableFuture.runAsync(() -> {
            try {
                // 设置SecurityContext
                SecurityContext asyncContext = securityContextHolderStrategy.createEmptyContext();
                asyncContext.setAuthentication(finalAuth);
                securityContextHolderStrategy.setContext(asyncContext);
                
                // 启动token有效性检查（每30秒检查一次）
                tokenCheckTask[0] = scheduler.scheduleAtFixedRate(() -> {
                    try {
                        if (session.completed) {
                            if (tokenCheckTask[0] != null) {
                                tokenCheckTask[0].cancel(false);
                            }
                            return;
                        }
                        
                        // 检查用户是否被强制下线
                        if (finalUsername != null && userService.isForcedOffline(finalUsername)) {
                            log.warn("用户被强制下线，终止SSE: username={}", finalUsername);
                            session.setAuthExpired();
                            
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("auth_expired")
                                        .data(Map.of(
                                                "message", "用户已被强制下线，请重新登录",
                                                "authExpired", true
                                        ), MediaType.APPLICATION_JSON));
                                emitter.complete();
                            } catch (IOException ignored) {}
                            
                            if (tokenCheckTask[0] != null) {
                                tokenCheckTask[0].cancel(false);
                            }
                            return;
                        }
                        
                        // 检查token是否仍然有效（可选，因为chat端点不强制验证）
                        if (finalJwtToken != null && !finalJwtToken.isEmpty() && !jwtUtil.validateToken(finalJwtToken)) {
                            log.warn("Token已过期，但不终止SSE（chat端点允许）: username={}", finalUsername);
                            // 注意：这里不终止SSE连接，只是记录日志
                            // 用户可以在token过期后继续使用当前会话
                        }
                    } catch (Exception e) {
                        log.debug("Token检查异常: {}", e.getMessage());
                    }
                }, 30, 30, TimeUnit.SECONDS);
                
                // 调用流式服务（实时反馈处理过程）
                streamingChatService.chatStream(
                        sessionId,
                        message,
                        finalIsAdmin,
                        // onStatus回调 - 实时反馈处理进度
                        status -> {
                            try {
                                if (!session.authExpired) {
                                    emitter.send(SseEmitter.event()
                                            .name("status")
                                            .data(status, MediaType.APPLICATION_JSON));
                                }
                            } catch (IOException e) {
                                log.debug("发送status事件失败: {}", e.getMessage());
                            }
                        },
                        // onComplete回调 - 返回完整回复
                        reply -> {
                            try {
                                String urlToOpen = OpenBrowserTool.getPendingUrl();
                                session.complete(urlToOpen);
                                
                                Map<String, Object> doneData = Map.of(
                                        "reply", reply,
                                        "urlToOpen", urlToOpen != null ? urlToOpen : "",
                                        "sessionId", sessionId
                                );
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data(doneData, MediaType.APPLICATION_JSON));
                                emitter.complete();
                            } catch (IOException e) {
                                log.debug("发送done事件失败: {}", e.getMessage());
                            } finally {
                                if (tokenCheckTask[0] != null) {
                                    tokenCheckTask[0].cancel(false);
                                }
                            }
                        },
                        // onError回调
                        error -> {
                            log.error("流式输出错误: {}", error.getMessage());
                            session.setError(error.getMessage());
                            
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(Map.of("message", "处理失败: " + error.getMessage()),
                                                MediaType.APPLICATION_JSON));
                            } catch (IOException e) {
                                log.debug("发送error事件失败: {}", e.getMessage());
                            }
                            emitter.completeWithError(error);
                            
                            if (tokenCheckTask[0] != null) {
                                tokenCheckTask[0].cancel(false);
                            }
                        }
                );

            } catch (Exception e) {
                log.error("流式聊天处理失败: {}", e.getMessage(), e);
                session.setError(e.getMessage());
                
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("message", "处理失败: " + e.getMessage()),
                                    MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    log.debug("发送error事件失败: {}", ex.getMessage());
                }
                emitter.completeWithError(e);
            } finally {
                securityContextHolderStrategy.clearContext();
                if (tokenCheckTask[0] != null) {
                    tokenCheckTask[0].cancel(false);
                }
            }
        });

        return emitter;
    }

    /**
     * 获取已缓存的流式输出内容（用于断线重连）
     */
    @GetMapping("/chat/stream/recover")
    public ResponseEntity<Map<String, Object>> recoverStream(@RequestParam String sessionId) {
        StreamSession session = streamSessionCache.get(sessionId);
        
        if (session == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "未找到该会话的缓存数据",
                    "needReconnect", false
            ));
        }
        
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("success", true);
        result.put("content", session.getContent());
        result.put("completed", session.completed);
        result.put("error", session.error != null ? session.error : "");
        result.put("urlToOpen", session.urlToOpen != null ? session.urlToOpen : "");
        result.put("authExpired", session.authExpired);
        result.put("needReconnect", !session.completed && !session.authExpired);
        
        return ResponseEntity.ok(result);
    }

    /**
     * 清除会话缓存
     */
    @DeleteMapping("/chat/stream/cache")
    public ResponseEntity<Map<String, Object>> clearStreamCache(@RequestParam String sessionId) {
        streamSessionCache.remove(sessionId);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "会话缓存已清除"
        ));
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/chat/stream/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "ok", 
                "type", "sse",
                "cachedSessions", String.valueOf(streamSessionCache.size())
        ));
    }
}
