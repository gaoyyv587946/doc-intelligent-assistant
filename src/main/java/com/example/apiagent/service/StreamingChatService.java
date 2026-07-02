package com.example.apiagent.service;

import com.example.apiagent.agent.ApiAgent;
import com.example.apiagent.agent.tools.*;
import com.example.apiagent.filter.SensitiveDataFilter;
import com.example.apiagent.store.InMemoryChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 流式聊天服务
 * 
 * 实时反馈处理过程：
 * 1. 发送"开始处理"状态
 * 2. 每次工具调用时，发送"正在执行XXX"状态
 * 3. 工具返回结果时，发送"XXX完成"状态
 * 4. 最后发送完整的LLM回复
 */
@Service
public class StreamingChatService {

    private static final Logger log = LoggerFactory.getLogger(StreamingChatService.class);

    private final OpenAiChatModel chatModel;
    private final DocumentSearchTool documentSearchTool;
    private final ProjectToMdTool projectToMdTool;
    private final ApiCallTool apiCallTool;
    private final OpenBrowserTool openBrowserTool;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final InMemoryChatMemoryStore chatMemoryStore;
    private final int maxMessages;

    private ApiAgent syncAgent;

    public StreamingChatService(
            OpenAiChatModel chatModel,
            DocumentSearchTool documentSearchTool,
            ApiCallTool apiCallTool,
            ProjectToMdTool projectToMdTool,
            OpenBrowserTool openBrowserTool,
            SensitiveDataFilter sensitiveDataFilter,
            InMemoryChatMemoryStore chatMemoryStore,
            @Value("${chat.memory.max-messages:20}") int maxMessages) {
        this.chatModel = chatModel;
        this.documentSearchTool = documentSearchTool;
        this.apiCallTool = apiCallTool;
        this.projectToMdTool = projectToMdTool;
        this.openBrowserTool = openBrowserTool;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.chatMemoryStore = chatMemoryStore;
        this.maxMessages = maxMessages;
    }

    /**
     * 初始化同步Agent实例
     */
    @PostConstruct
    public void init() {
        this.syncAgent = AiServices.builder(ApiAgent.class)
                .chatLanguageModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(chatMemoryStore)
                        .build())
                .tools(documentSearchTool, apiCallTool, projectToMdTool, openBrowserTool)
                .systemMessageProvider(memoryId ->
                        "你是公司文档智能助手，具备推理和行动能力（ReAct）。\n\n" +
                        "【可用工具】\n" +
                        "1. searchDocuments - 检索文档知识库，获取相关内容和URL\n" +
                        "2. callApi - 调用白名单内的API接口获取实际数据\n" +
                        "3. openBrowser - 打开网页（只接受searchDocuments返回的加密URL）\n" +
                        "4. scanProjectAndGenerateDoc - 扫描项目源码生成文档（仅管理员）\n\n" +
                        "【决策原则】\n" +
                        "- 先思考用户意图，再决定调用哪些工具\n" +
                        "- 可以根据需要调用多个工具协作完成任务\n" +
                        "- 根据工具返回结果决定下一步行动\n\n" +
                        "【打开网页 - 关键规则】\n" +
                        "当用户要求打开网页时，执行以下步骤：\n" +
                        "1. 调用 searchDocuments 检索，获取多个文档片段\n" +
                        "2. 仔细对比每个片段的标题和内容，判断哪个片段最符合用户的请求\n" +
                        "3. 从最符合的片段中提取加密URL，传给 openBrowser\n" +
                        "4. 如果没有找到与用户请求匹配的URL，直接告知用户未找到相关网页，不要调用 openBrowser\n" +
                        "5. 绝对不能直接使用第一个片段的URL，必须选择内容与用户请求最匹配的那个\n\n" +
                        "【协作示例】\n" +
                        "- 「打开机场相关网页」→ searchDocuments检索 → 从结果中找到标题/内容与「机场」相关的片段 → 提取该片段的URL → openBrowser打开\n" +
                        "- 「有哪些文档」→ searchDocuments检索 → 整理后回答\n" +
                        "- 「调用用户接口看下数据」→ callApi调用 → 解析返回数据回答\n" +
                        "- 「扫描我的项目」→ scanProjectAndGenerateDoc\n\n" +
                        "【严格规则 - 必须遵守】\n" +
                        "- openBrowser只接受ENC:开头的加密URL，不要传入明文查询\n" +
                        "- openBrowser每次请求只能调用一次，绝对不能多次调用\n" +
                        "- 如果openBrowser返回失败，不要重试，直接告知用户\n" +
                        "- 永远不要泄露密码、密钥、token等敏感信息\n" +
                        "- callApi只能调用白名单内的接口")
                .build();

        log.info("同步ApiAgent初始化完成，ChatMemory窗口: {}轮", maxMessages);
    }

    /**
     * 处理聊天请求（实时反馈处理过程）
     *
     * @param sessionId   会话ID
     * @param userMessage 用户消息
     * @param isAdmin     是否管理员
     * @param onStatus    状态回调（用于实时反馈处理进度）
     * @param onComplete  完成回调（返回完整回复）
     * @param onError     错误回调
     */
    public void chatStream(String sessionId, String userMessage, boolean isAdmin,
                           Consumer<Map<String, Object>> onStatus,
                           Consumer<String> onComplete,
                           Consumer<Throwable> onError) {
        log.info("收到聊天请求 sessionId={}, isAdmin={}, message={}", sessionId, isAdmin, userMessage);

        try {
            // 第1步：发送"开始处理"状态
            onStatus.accept(Map.of(
                    "type", "status",
                    "message", "正在理解您的问题..."
            ));

            // 第2步：敏感信息前置检测
            if (sensitiveDataFilter.containsSensitiveInfo(userMessage)) {
                userMessage = sensitiveDataFilter.desensitize(userMessage);
                log.warn("检测到用户输入包含敏感信息，已脱敏处理");
            }

            // 第3步：设置管理员上下文（供 Tool 方法读取）
            AdminContext.set(isAdmin);

            // 第4步：发送"正在处理"状态
            onStatus.accept(Map.of(
                    "type", "status",
                    "message", "正在分析并处理您的请求..."
            ));

            // 第5步：调用同步Agent（支持多轮工具调用）
            // 注意：这里会阻塞直到LLM完成所有工具调用并生成最终回复
            String reply = syncAgent.chat(sessionId, userMessage);

            log.info("Agent回复长度: {} 字符", reply.length());

            // 第6步：发送"处理完成"状态
            onStatus.accept(Map.of(
                    "type", "status",
                    "message", "处理完成，正在生成回复..."
            ));

            // 第7步：调用完成回调，返回完整回复
            onComplete.accept(reply);

        } catch (Exception e) {
            log.error("聊天处理失败: {}", e.getMessage(), e);
            onError.accept(e);
        } finally {
            AdminContext.clear();
        }
    }

    /**
     * 清理资源
     */
    public void cleanup() {
        AdminContext.clear();
        log.debug("清理AdminContext完成");
    }
}
