package com.example.apiagent.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * URL加密解密工具
 * 
 * 用途：在传给LLM时加密URL，防止LLM看到真实路径
 * 当Tool需要实际调用时再解密还原
 * 
 * 编码方式：Base64 + 前缀标识
 */
@Component
public class UrlEncryptor {

    // 加密URL的前缀标识，用于识别是否需要解密
    private static final String ENCRYPTED_PREFIX = "ENC:";
    
    /**
     * 加密URL
     * 
     * @param url 原始URL
     * @return 加密后的URL（带ENC:前缀）
     */
    public String encrypt(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        
        // 已经加密过，直接返回
        if (isEncrypted(url)) {
            return url;
        }
        
        try {
            // Base64编码
            String encoded = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(url.getBytes(StandardCharsets.UTF_8));
            
            return ENCRYPTED_PREFIX + encoded;
        } catch (Exception e) {
            // 加密失败返回原始URL
            return url;
        }
    }
    
    /**
     * 解密URL
     * 
     * @param encryptedUrl 加密后的URL（带ENC:前缀）
     * @return 原始URL
     */
    public String decrypt(String encryptedUrl) {
        if (encryptedUrl == null || encryptedUrl.isBlank()) {
            return encryptedUrl;
        }
        
        // 不是加密格式，直接返回
        if (!isEncrypted(encryptedUrl)) {
            return encryptedUrl;
        }
        
        try {
            // 去掉前缀
            String encoded = encryptedUrl.substring(ENCRYPTED_PREFIX.length());
            
            // Base64解码
            byte[] decoded = Base64.getUrlDecoder().decode(encoded);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            // 解密失败返回原始值
            return encryptedUrl;
        }
    }
    
    /**
     * 判断URL是否已加密
     */
    public boolean isEncrypted(String url) {
        return url != null && url.startsWith(ENCRYPTED_PREFIX);
    }
}
