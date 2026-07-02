package com.example.apiagent.agent.tools;

import com.example.apiagent.security.UrlEncryptor;
import com.example.apiagent.service.HybridSearchService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档检索工具（混合检索版）
 * Agent通过此工具在RAG中搜索相关文档内容
 *
 * 检索流程：
 * 1. 意图预处理 - 提取API路径、关键词等结构化条件
 * 2. 精确过滤 - metadata匹配，命中直接返回
 * 3. 并行召回 - 向量检索 + BM25关键词检索
 * 4. RRF融合 - 倒数排名融合算法
 * 
 * 安全设计：
 * - URL加密：返回给LLM时对URL进行加密，防止LLM看到真实路径
 */
@Component
public class DocumentSearchTool {

    private static final Logger log = LoggerFactory.getLogger(DocumentSearchTool.class);

    private final HybridSearchService hybridSearchService;
    private final UrlEncryptor urlEncryptor;

    public DocumentSearchTool(HybridSearchService hybridSearchService,
                             UrlEncryptor urlEncryptor) {
        this.hybridSearchService = hybridSearchService;
        this.urlEncryptor = urlEncryptor;
    }

    @Tool("搜索文档知识库，根据关键词或描述查找相关文档内容、书签URL等信息。可用于回答问题，也可用于查找后续操作的目标（如要打开的网页）。返回结果中的URL已加密，如需打开请使用openBrowser工具并传入匹配用户请求的加密URL。")
    public String searchDocuments(
            @P("查询内容，如关键词、标题、功能描述或具体问题，例如'用户注册'、'错误处理'或'500报错原因'") String query) {

        log.info("文档检索查询: {}", query);

        try {
            // 使用混合检索服务
            List<HybridSearchService.SearchResult> results = hybridSearchService.search(query);

            if (results.isEmpty()) {
                log.info("未找到相关文档片段");
                return "未找到与 '" + query + "' 相关的文档内容。建议检查：\n"
                        + "1. 文档是否已上传并加载\n"
                        + "2. 查询关键词是否准确\n"
                        + "3. 尝试用不同的关键词描述";
            }

            // 格式化返回结果
            StringBuilder result = new StringBuilder();
            result.append("找到 ").append(results.size()).append(" 个相关文档片段：\n\n");

            // 收集所有有URL的片段信息，最后汇总
            List<String> urlSummary = new java.util.ArrayList<>();

            for (int i = 0; i < results.size(); i++) {
                HybridSearchService.SearchResult match = results.get(i);
                int fragmentIndex = i + 1;
                result.append("=== 片段 ").append(fragmentIndex)
                      .append(" | 相关度: ").append(String.format("%.4f", match.score()))
                      .append(" | 来源: ").append(match.source())
                      .append(" ===\n");

                // 附带metadata信息
                String title = null;
                if (match.metadata() != null) {
                    String fileName = match.metadata().get("file_name");
                    if (fileName != null) {
                        result.append("文件: ").append(fileName);
                    }
                    String apiPath = match.metadata().get("api_path");
                    if (apiPath != null) {
                        String method = match.metadata().getOrDefault("http_method", "");
                        result.append(" | 接口: ").append(method).append(" ").append(apiPath);
                    }
                    title = match.metadata().get("title");
                    if (title != null) {
                        result.append(" | 标题: ").append(title);
                    }
                    result.append("\n");

                    // URL加密并单独一行展示
                    String url = match.metadata().get("url");
                    if (url != null && !url.isBlank()) {
                        String encryptedUrl = urlEncryptor.encrypt(url);
                        result.append("【可打开】URL: ").append(encryptedUrl).append("\n");
                        String summaryTitle = title != null ? title : (fileName != null ? fileName : "片段" + fragmentIndex);
                        urlSummary.add("片段" + fragmentIndex + " [" + summaryTitle + "]: " + encryptedUrl);
                    }
                }

                // 内容中的URL也需要加密
                String content = match.content();
                if (content != null) {
                    content = encryptUrlsInContent(content);
                }
                result.append(content).append("\n\n");
            }

            // 汇总所有可打开的URL，帮助LLM选择
            if (!urlSummary.isEmpty()) {
                result.append("--- 可打开的URL汇总（请根据用户请求选择最匹配的） ---\n");
                for (String summary : urlSummary) {
                    result.append(summary).append("\n");
                }
                result.append("提示：请对比用户请求与各片段的标题/内容，选择最匹配的URL传给openBrowser。如果没有匹配的，不要打开。\n");
            }

            log.info("返回 {} 个相关片段，其中 {} 个包含URL", results.size(), urlSummary.size());
            return result.toString();

        } catch (Exception e) {
            log.error("文档检索失败: {}", e.getMessage(), e);
            return "文档检索出错: " + e.getMessage();
        }
    }

    /**
     * 加密内容中的所有URL
     * 使用通用URL模式匹配，确保所有http/https链接都被加密
     */
    private String encryptUrlsInContent(String content) {
        if (content == null) return content;
        
        // 通用URL模式：匹配所有http/https开头的URL
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(https?://\\S+)");
        java.util.regex.Matcher matcher = pattern.matcher(content);
        
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            // 去掉末尾可能的标点符号（.  ,  ）等），这些不属于URL
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
}
