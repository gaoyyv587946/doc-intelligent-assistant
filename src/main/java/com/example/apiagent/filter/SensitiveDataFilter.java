package com.example.apiagent.filter;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感数据过滤器
 * 四层防护：文档入库前、检索结果返回、LLM请求构造、LLM响应输出
 *
 * 脱敏策略：将password=abc123替换为password=***
 */
@Component
public class SensitiveDataFilter {

    /** 需要脱敏的关键词列表（小写） */
    private static final List<String> SENSITIVE_KEYS = List.of(
            "password", "passwd", "pwd", "secret", "token",
            "apikey", "api_key", "api-key", "credential",
            "authorization", "cookie", "sessionid"
    );

    /** 脱敏正则：匹配 key=value 或 key: value 格式 */
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(" + String.join("|", SENSITIVE_KEYS) + ")\\s*[:=]\\s*\\S+",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 脱敏处理：将敏感字段的值替换为***
     * 例如：password=abc123 → password=***
     */
    public String desensitize(String input) {
        if (input == null) return null;
        return SENSITIVE_PATTERN.matcher(input).replaceAll("$1=***");
    }

    /**
     * 检测是否包含敏感信息
     */
    public boolean containsSensitiveInfo(String input) {
        if (input == null) return false;
        return SENSITIVE_PATTERN.matcher(input).find();
    }

    /**
     * 安全提示消息
     */
    public String getSensitiveWarning() {
        return "【安全提示】您输入的内容可能包含敏感信息（密码/token等），已自动脱敏处理。"
                + "请注意：系统不会将密码等敏感数据发送给AI模型。";
    }
}
