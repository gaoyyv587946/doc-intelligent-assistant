package com.example.apiagent.config;

import com.example.apiagent.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security配置
 * 
 * 策略调整：
 * - SSE/chat端点：放宽为permitAll，由Controller内部自行校验权限
 * - 其他端点：保持原有的权限控制
 * - 使用@EnableMethodSecurity启用@PreAuthorize注解支持
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity  // 启用@PreAuthorize支持
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 公开端点
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/tuning/**").permitAll()
                .requestMatchers("/api/search/**").permitAll()
                // SSE/chat端点：放宽权限，避免长连接期间token过期导致的问题
                .requestMatchers("/api/chat/**").permitAll()
                // 需要ADMIN权限的端点
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                .requestMatchers("/api/documents/**").hasRole("ADMIN")
                .requestMatchers("/api/notes/**").hasRole("ADMIN")
                // 其他请求需要认证
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
