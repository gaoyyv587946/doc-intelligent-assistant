package com.example.apiagent.agent.tools;

import com.example.apiagent.filter.ApiWhitelistFilter;
import com.example.apiagent.filter.SensitiveDataFilter;
import com.example.apiagent.security.UrlEncryptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * API调用工具
 * Agent通过此工具尝试调用外部API获取实际数据
 *
 * 安全设计：
 * 1. 白名单校验：仅允许调用配置的接口前缀
 * 2. 敏感信息检测：拒绝包含密码/token的请求
 * 3. URL加密：支持加密URL输入，返回结果中的URL也会加密
 * 4. 重试策略：渐进放宽参数（去掉时间限制→扩大范围→全量查询）
 */
@Component
public class ApiCallTool {

    private static final Logger log = LoggerFactory.getLogger(ApiCallTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ApiWhitelistFilter whitelistFilter;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final UrlEncryptor urlEncryptor;
    private final RestTemplate restTemplate;

    public ApiCallTool(
            ApiWhitelistFilter whitelistFilter,
            SensitiveDataFilter sensitiveDataFilter,
            UrlEncryptor urlEncryptor) {
        this.whitelistFilter = whitelistFilter;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.urlEncryptor = urlEncryptor;
        this.restTemplate = new RestTemplate();
    }

    @Tool("调用白名单内的API接口获取实际数据。当用户需要查看接口实际返回数据时使用，必须是白名单内的接口。支持传入ENC:开头的加密URL。")
    public String callApi(
            @P("接口URL（支持ENC:开头的加密URL或明文URL），如 ENC:xxx 或 https://api.company.com/users") String url,
            @P("HTTP方法：GET/POST/PUT/DELETE") String method,
            @P("请求参数JSON（GET时为query参数，POST时为body），如 {\"page\":1,\"size\":10}。可为空字符串") String params,
            @P("请求头JSON，如 {\"Content-Type\":\"application/json\"}。可为空字符串") String headers) {

        // 解密URL（如果是加密的）
        String realUrl = urlEncryptor.isEncrypted(url.trim()) ? urlEncryptor.decrypt(url.trim()) : url.trim();
        log.info("API调用请求: {} {} params={} headers={}", method, realUrl, params, headers);

        // 第1步：白名单校验
        if (!whitelistFilter.isAllowed(realUrl)) {
            log.warn("白名单拒绝: {}", realUrl);
            return "接口不在可调用白名单内，禁止访问。";
        }

        // 第2步：敏感信息检测
        if (sensitiveDataFilter.containsSensitiveInfo(params)
                || sensitiveDataFilter.containsSensitiveInfo(headers)) {
            log.warn("检测到敏感信息，拒绝执行");
            return "请求参数或头中包含敏感信息（密码/token等），已拒绝执行。请去除敏感字段后重试。";
        }

        // 第3步：执行调用 + 重试逻辑
        String currentParams = params;
        int maxRetry = whitelistFilter.getMaxRetry();
        for (int attempt = 1; attempt <= maxRetry; attempt++) {
            try {
                HttpHeaders httpHeaders = parseHeaders(headers);
                HttpEntity<String> entity = new HttpEntity<>(currentParams, httpHeaders);

                ResponseEntity<String> response = restTemplate.exchange(
                        realUrl, HttpMethod.valueOf(method), entity, String.class);

                if (response.getStatusCode().is2xxSuccessful() && hasBody(response.getBody())) {
                    // 对返回数据中的URL进行加密
                    String body = encryptUrlsInResponse(response.getBody());
                    String result = "调用成功(第" + attempt + "次尝试，状态码: " + response.getStatusCode().value() + "):\n"
                            + body;
                    log.info("API调用成功: {} {}", method, realUrl);
                    return result;
                }

                // 有响应但无数据 → 调整参数重试
                log.info("API返回无数据(第{}次)，调整参数重试", attempt);
                if (attempt < maxRetry) {
                    currentParams = adjustParams(currentParams, attempt);
                }
            } catch (Exception e) {
                log.warn("API调用异常(第{}次): {}", attempt, e.getMessage());
                if (attempt < maxRetry) {
                    currentParams = adjustParams(currentParams, attempt);
                } else {
                    return "调用失败(已尝试" + maxRetry + "次): " + e.getMessage();
                }
            }
        }

        return "调用失败：已尝试" + maxRetry + "次，均未获取到有效数据。建议：\n"
                + "1. 检查接口是否正常运行\n"
                + "2. 检查参数是否正确\n"
                + "3. 稍后重试";
    }

    /**
     * 加密API响应中的URL
     * 防止真实URL通过API返回数据泄露给LLM
     */
    private String encryptUrlsInResponse(String body) {
        if (body == null || body.isBlank()) return body;
        
        java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile("(https?://\\S+)");
        java.util.regex.Matcher matcher = urlPattern.matcher(body);
        
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            // 去掉末尾标点
            String trailing = "";
            String cleanUrl = url;
            while (cleanUrl.length() > 0 && ".,;:!?)]}>'\"".indexOf(cleanUrl.charAt(cleanUrl.length() - 1)) >= 0) {
                trailing = cleanUrl.charAt(cleanUrl.length() - 1) + trailing;
                cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
            }
            String encrypted = urlEncryptor.encrypt(cleanUrl) + trailing;
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(encrypted));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 重试策略：渐进放宽参数
     * 第1次失败 → 去掉时间限制参数（查询全部）
     * 第2次失败 → 去除所有过滤参数（查询全量）
     */
    private String adjustParams(String params, int attempt) {
        if (params == null || params.isBlank()) return params;

        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(params);

            switch (attempt) {
                case 1:
                    // 去掉时间相关参数
                    List.of("startTime", "endTime", "startDate", "endDate",
                            "start_time", "end_time", "start_date", "end_date",
                            "from", "to", "since", "until", "date", "time")
                            .forEach(node::remove);
                    break;
                case 2:
                    // 去掉分页参数，查全量
                    List.of("page", "pageNum", "page_num", "pageNo", "page_no",
                            "size", "pageSize", "page_size", "limit", "offset")
                            .forEach(node::remove);
                    break;
                default:
                    // 返回空参数
                    return "{}";
            }

            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            return params;
        }
    }

    /**
     * 解析请求头JSON
     */
    private HttpHeaders parseHeaders(String headersJson) {
        HttpHeaders httpHeaders = new HttpHeaders();

        if (headersJson == null || headersJson.isBlank()) {
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
            return httpHeaders;
        }

        try {
            JsonNode node = objectMapper.readTree(headersJson);
            node.fields().forEachRemaining(entry ->
                    httpHeaders.add(entry.getKey(), entry.getValue().asText()));
        } catch (Exception e) {
            log.warn("解析请求头失败: {}", e.getMessage());
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        }

        return httpHeaders;
    }

    /**
     * 检查响应体是否有有效数据
     * []、{}、空字符串、null都视为"无数据"
     */
    private boolean hasBody(String body) {
        if (body == null || body.isBlank()) return false;
        String trimmed = body.trim();
        return !"[]".equals(trimmed) && !"{}".equals(trimmed) && !"null".equals(trimmed);
    }
}
