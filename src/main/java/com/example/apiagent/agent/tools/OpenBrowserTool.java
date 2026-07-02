package com.example.apiagent.agent.tools;

import com.example.apiagent.security.UrlEncryptor;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 浏览器打开工具（前端打开模式）
 * 
 * 工作流程：
 * 1. LLM先调用searchDocuments检索文档，获取加密URL（ENC:xxx）
 * 2. LLM再调用openBrowser传入加密URL
 * 3. 本工具将URL存入ThreadLocal，由ChatService提取后通过响应返回前端
 * 4. 前端用 window.open() 在用户自己的浏览器中打开
 * 
 * 不再在服务端打开浏览器，支持远程部署场景（A机器部署，B用户在C机器访问）
 */
@Component
public class OpenBrowserTool {

    private static final Logger log = LoggerFactory.getLogger(OpenBrowserTool.class);

    // 防重复调用：记录当前请求是否已调用过openBrowser
    private static final ThreadLocal<Boolean> hasCalled = ThreadLocal.withInitial(() -> false);
    // 记录待打开的URL，由ChatService提取后返回前端
    private static final ThreadLocal<String> pendingUrl = new ThreadLocal<>();

    private final UrlEncryptor urlEncryptor;

    public OpenBrowserTool(UrlEncryptor urlEncryptor) {
        this.urlEncryptor = urlEncryptor;
    }

    /**
     * 重置调用状态（在请求开始时调用）
     */
    public static void resetCallState() {
        hasCalled.set(false);
        pendingUrl.remove();
    }

    /**
     * 获取待打开的URL（由ChatService调用）
     */
    public static String getPendingUrl() {
        return pendingUrl.get();
    }

    /**
     * 打开浏览器访问网页（每次请求只能调用一次）
     * 只接受searchDocuments返回的加密URL（ENC:开头）
     * 实际打开操作由前端完成，这里只记录URL
     */
    @Tool("打开浏览器访问书签中的网页。只接受searchDocuments返回的加密URL（ENC:开头），不要传入明文查询。如果需要查找网页，请先调用searchDocuments获取加密URL。每次请求只能调用一次，如果失败不要重试。")
    public String openBrowser(
            @P("searchDocuments返回的加密URL（ENC:开头），例如：'ENC:aHR0cHM6Ly9iYWlkdS5jb20='") String encryptedUrl) {
        
        log.info("收到打开浏览器请求: {}", encryptedUrl);
        
        // 检查是否已经调用过
        if (hasCalled.get()) {
            log.warn("openBrowser已被调用过，拒绝重复调用");
            return "无法打开：每次请求只能打开一个网页，已经调用过openBrowser了。";
        }
        
        // 标记为已调用
        hasCalled.set(true);
        
        if (encryptedUrl == null || encryptedUrl.isBlank()) {
            return "请提供searchDocuments返回的加密URL（ENC:开头）。如果需要查找网页，请先调用searchDocuments。";
        }

        // 只处理加密URL，不接受明文查询
        if (!urlEncryptor.isEncrypted(encryptedUrl.trim())) {
            return "无法打开：请传入searchDocuments返回的加密URL（ENC:开头），不要传入明文查询。请先调用searchDocuments获取加密URL。";
        }
        
        // 解密URL验证格式
        String decryptedUrl = urlEncryptor.decrypt(encryptedUrl.trim());
        log.info("解密URL: {} -> {}", encryptedUrl, decryptedUrl);
        
        if (!isValidUrl(decryptedUrl)) {
            return "无法打开：解密后的URL格式无效。";
        }
        
        // 将加密URL存入ThreadLocal，前端收到后解密并打开
        pendingUrl.set(encryptedUrl.trim());
        log.info("URL已记录，等待前端打开: {}", decryptedUrl);
        
        return "正在为您打开网页...";
    }

    /**
     * 验证URL是否有效
     */
    private boolean isValidUrl(String url) {
        if (url == null || url.isBlank()) return false;
        
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return false;
        }
        
        if (url.contains(" ") || url.contains("\n") || url.contains("\r")) {
            return false;
        }
        
        if (url.length() < 10) {
            return false;
        }
        
        return true;
    }
}
