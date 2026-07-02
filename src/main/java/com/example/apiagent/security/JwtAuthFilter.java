package com.example.apiagent.security;

import com.example.apiagent.repository.UserRepository;
import com.example.apiagent.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT认证过滤器
 * 
 * 增强功能：
 * - 检查 token 黑名单（强制下线）
 * - 检查用户是否被强制踢出
 * - 传递 admin 角色到 Security Context
 * 
 * 特殊处理：
 * - /api/chat/** 端点：尝试解析token但不强制验证失败
 *   这样可以避免SSE长连接期间token过期导致的问题
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final UserService userService;

    public JwtAuthFilter(JwtUtil jwtUtil, UserRepository userRepository, UserService userService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String uri = request.getRequestURI();
        
        // 公开端点跳过验证（登录、搜索、调优等）
        if (isPublicEndpoint(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        // chat端点：尝试解析token，但不强制验证失败
        boolean isChatEndpoint = uri.startsWith("/api/chat/");
        
        String authHeader = request.getHeader("Authorization");
        
        // 支持从URL参数获取token（用于SSE等不支持自定义headers的场景）
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            // 尝试从URL参数获取token
            token = request.getParameter("token");
        }

        if (token != null && !token.isEmpty()) {
            try {
                if (jwtUtil.validateToken(token)) {
                    String username = jwtUtil.getUsernameFromToken(token);
                    String jti = jwtUtil.getJti(token);
                    boolean isAdmin = jwtUtil.isAdmin(token);

                    // 检查黑名单（token是否被吊销）
                    try {
                        if (userRepository.isTokenBlacklisted(jti)) {
                            if (!isChatEndpoint) {
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token已失效");
                                return;
                            }
                            // chat端点：继续执行，但不设置认证信息
                        }
                    } catch (Exception ignored) {}

                    // 检查用户是否被强制下线
                    if (userService.isForcedOffline(username)) {
                        if (!isChatEndpoint) {
                            response.sendError(HttpServletResponse.SC_FORBIDDEN, "用户已被强制下线");
                            return;
                        }
                        // chat端点：继续执行，但不设置认证信息
                    }

                    // 刷新在线时间
                    userService.refreshOnline(username);

                    // 构建认证对象（携带角色信息）
                    List<SimpleGrantedAuthority> authorities = isAdmin
                            ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))
                            : List.of(new SimpleGrantedAuthority("ROLE_USER"));

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    // token无效
                    if (!isChatEndpoint) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token无效");
                        return;
                    }
                    // chat端点：继续执行，但不设置认证信息
                }
            } catch (Exception e) {
                // token解析异常
                if (!isChatEndpoint) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "Token解析失败");
                    return;
                }
                // chat端点：继续执行，但不设置认证信息
            }
        } else {
            // 无token
            if (!isChatEndpoint) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "未提供认证信息");
                return;
            }
            // chat端点：继续执行，但不设置认证信息
        }

        filterChain.doFilter(request, response);
    }
    
    /**
     * 判断是否为公开端点（不需要认证）
     */
    private boolean isPublicEndpoint(String uri) {
        // 登录/注册端点
        if (uri.startsWith("/api/auth/")) return true;
        // 搜索分析端点
        if (uri.startsWith("/api/search/")) return true;
        // 权重调优端点
        if (uri.startsWith("/api/tuning/")) return true;
        // 静态资源
        if (uri.startsWith("/assets/") || uri.startsWith("/static/")) return true;
        return false;
    }
}
