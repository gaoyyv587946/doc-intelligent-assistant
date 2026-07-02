package com.example.apiagent.filter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * API白名单过滤器
 * 控制Agent可调用的接口范围，用前缀匹配而非完整URL
 *
 * 设计要点：
 * - 白名单在Tool内部执行，LLM无法绕过
 * - 前缀匹配降低维护成本（同一前缀下多个子路径接口）
 *
 * 使用 @ConfigurationProperties 绑定 YAML list，
 * @Value 无法可靠注入 YAML list 到 List<String>
 */
@Component
@ConfigurationProperties(prefix = "api.whitelist")
public class ApiWhitelistFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiWhitelistFilter.class);

    private List<String> prefixes = List.of();
    private boolean enabled = true;
    private int maxRetry = 3;

    @PostConstruct
    public void logConfig() {
        log.info("API白名单: enabled={}, prefixes={}, maxRetry={}", enabled, prefixes, maxRetry);
    }

    // ==================== Getters & Setters（@ConfigurationProperties 需要） ====================

    public List<String> getPrefixes() {
        return prefixes;
    }

    public void setPrefixes(List<String> prefixes) {
        this.prefixes = prefixes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    // ==================== 业务方法 ====================

    /**
     * 校验URL是否在白名单内
     */
    public boolean isAllowed(String url) {
        if (!enabled) return true;
        if (url == null || url.isBlank()) return false;
        return prefixes.stream().anyMatch(url::startsWith);
    }

    /**
     * 获取白名单前缀列表（用于错误提示）
     */
    public List<String> getWhitelistPrefixes() {
        return prefixes;
    }
}
