package com.example.apiagent.model;

/**
 * 聊天响应DTO
 */
public class ChatResponse {

    private String sessionId;
    private String reply;
    private boolean sensitiveDetected;
    /** 需要在前端打开的URL（加密格式ENC:xxx），为null时不打开 */
    private String urlToOpen;

    public ChatResponse() {}

    public ChatResponse(String sessionId, String reply, boolean sensitiveDetected) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.sensitiveDetected = sensitiveDetected;
    }

    public ChatResponse(String sessionId, String reply, boolean sensitiveDetected, String urlToOpen) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.sensitiveDetected = sensitiveDetected;
        this.urlToOpen = urlToOpen;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public boolean isSensitiveDetected() { return sensitiveDetected; }
    public void setSensitiveDetected(boolean sensitiveDetected) { this.sensitiveDetected = sensitiveDetected; }

    public String getUrlToOpen() { return urlToOpen; }
    public void setUrlToOpen(String urlToOpen) { this.urlToOpen = urlToOpen; }
}
