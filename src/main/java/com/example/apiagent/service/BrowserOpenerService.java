package com.example.apiagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 浏览器打开服务
 * 
 * 功能：
 * 1. 使用系统默认浏览器打开指定URL
 * 2. 支持Windows、Mac、Linux系统
 * 3. URL格式校验
 */
@Service
public class BrowserOpenerService {

    private static final Logger log = LoggerFactory.getLogger(BrowserOpenerService.class);

    /**
     * 使用默认浏览器打开指定URL
     * 
     * @param url 要打开的网页地址
     * @return 操作结果描述
     */
    public String openUrl(String url) {
        if (url == null || url.isBlank()) {
            return "错误：URL不能为空";
        }

        // URL格式校验和规范化
        String normalizedUrl = normalizeUrl(url);
        if (normalizedUrl == null) {
            return "错误：无效的URL格式: " + url;
        }

        try {
            // 检查Desktop API是否可用
            if (Desktop.isDesktopSupported()) {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.BROWSE)) {
                    URI uri = new URI(normalizedUrl);
                    desktop.browse(uri);
                    log.info("已打开浏览器访问: {}", normalizedUrl);
                    return "成功打开浏览器访问: " + normalizedUrl;
                } else {
                    return "错误：当前系统不支持Desktop.BROWSE操作";
                }
            } else {
                // 尝试使用命令行方式打开
                return openWithCommand(normalizedUrl);
            }
        } catch (URISyntaxException e) {
            log.error("URL格式错误: {}", normalizedUrl, e);
            return "错误：URL格式错误 - " + e.getMessage();
        } catch (Exception e) {
            log.error("打开浏览器失败: {}", normalizedUrl, e);
            return "错误：打开浏览器失败 - " + e.getMessage();
        }
    }

    /**
     * 规范化URL格式
     * 
     * @param url 原始URL
     * @return 规范化后的URL，无效返回null
     */
    private String normalizeUrl(String url) {
        if (url == null) return null;
        
        url = url.trim();
        
        // 如果没有协议，添加https://
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("ftp://")) {
            // 检查是否是有效的域名格式
            if (url.matches("^[a-zA-Z0-9][a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}.*$")) {
                url = "https://" + url;
            } else {
                return null;
            }
        }

        try {
            URI uri = new URI(url);
            // 验证URI格式
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return url;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 使用系统命令打开浏览器（备用方案）
     */
    private String openWithCommand(String url) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder builder;

            if (os.contains("win")) {
                // Windows: 使用rundll32打开默认浏览器
                builder = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                // macOS: 使用open命令
                builder = new ProcessBuilder("open", url);
            } else if (os.contains("nux") || os.contains("nix")) {
                // Linux: 使用xdg-open
                builder = new ProcessBuilder("xdg-open", url);
            } else {
                return "错误：不支持的操作系统: " + os;
            }

            builder.start();
            log.info("通过系统命令打开浏览器访问: {}", url);
            return "成功打开浏览器访问: " + url;
        } catch (Exception e) {
            log.error("通过系统命令打开浏览器失败: {}", url, e);
            return "错误：打开浏览器失败 - " + e.getMessage();
        }
    }
}
