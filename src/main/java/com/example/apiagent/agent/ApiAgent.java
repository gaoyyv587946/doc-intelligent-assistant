package com.example.apiagent.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

/**
 * 文档智能助手Agent接口
 * 使用LangChain4j AiServices声明式定义
 *
 * 工作流程：
 * 1. 用户提问 → LLM理解意图
 * 2. LLM决定是否调用searchDocuments工具检索文档
 * 3. LLM根据检索结果组织回答
 * 4. 若需要查看实际数据，LLM决定是否调用callApi工具
 * 5. LLM整合所有信息给出最终回答
 */
public interface ApiAgent {

    /**
     * 与Agent对话（同步方式）
     *
     * @param sessionId   会话ID（用于隔离不同用户的ChatMemory）
     * @param userMessage 用户输入的消息
     * @return Agent的回答
     */
    String chat(@MemoryId String sessionId, @UserMessage String userMessage);

    /**
     * 与Agent对话（流式方式）
     * 
     * 注意：使用流式方式时，需要在AiServices.builder中配置streamingChatLanguageModel
     * 返回TokenStream后，需要注册回调处理token：
     * - onPartialResponse: 每收到一个token时调用
     * - onCompleteResponse: 流式完成时调用
     * - onError: 发生错误时调用
     * 最后调用start()开始流式输出
     *
     * @param sessionId   会话ID（用于隔离不同用户的ChatMemory）
     * @param userMessage 用户输入的消息
     * @return TokenStream 用于注册回调和启动流式输出
     */
    TokenStream chatStream(@MemoryId String sessionId, @UserMessage String userMessage);
}

