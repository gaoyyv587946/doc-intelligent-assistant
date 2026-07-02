package com.example.apiagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文档检索智能知识回复AI Agent 启动类
 *
 * 功能：
 * 1. RAG向量库文档检索
 * 2. ReAct模式智能问答
 * 3. API接口自动调用尝试
 * 4. 敏感信息保护 + 白名单控制
 */
@SpringBootApplication
public class ApiAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiAgentApplication.class, args);
    }
}
