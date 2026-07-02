package com.example.apiagent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 智能查询扩展服务
 *
 * 三层扩展策略（由轻到重）：
 *
 * 1. 语料库词汇扩展（默认开启，零成本）
 *    - 从已索引文档中提取高频领域词汇（标题、关键词、参数名）
 *    - 基于查询token与领域词汇的字符重叠做关联扩展
 *    - 无需LLM调用，完全基于本地索引数据
 *
 * 2. LLM智能改写（可选，适合口语化查询）
 *    - 仅对口语化查询触发LLM改写
 *    - 注入文档上下文（已有API列表），让LLM改写更精准
 *    - 缓存改写结果避免重复调用
 *
 * 3. 查询归一化（始终开启）
 *    - 去除多余空白、统一大小写
 *    - 提取结构化信息（API路径、HTTP方法）
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    private final OpenAiChatModel chatModel;
    private final KeywordIndexService keywordIndexService;
    private final boolean llmEnabled;
    private final int maxCacheSize;

    /** 查询改写缓存 */
    private final ConcurrentHashMap<String, List<String>> rewriteCache = new ConcurrentHashMap<>();

    /** 口语化查询特征词（判断是否需要LLM介入） */
    private static final Set<String> COLLOQUIAL_INDICATORS = Set.of(
            "怎么", "如何", "怎样", "啥", "啥意思", "什么意思",
            "帮我", "能不能", "可以", "想", "要", "需要",
            "有没有", "哪里", "哪个", "为什么", "为啥",
            "怎么办", "搞不定", "报错", "出错", "失败"
    );

    public QueryRewriteService(
            OpenAiChatModel chatModel,
            KeywordIndexService keywordIndexService,
            @Value("${rag.query-rewrite.enabled:false}") boolean llmEnabled,
            @Value("${rag.query-rewrite.max-cache-size:500}") int maxCacheSize) {
        this.chatModel = chatModel;
        this.keywordIndexService = keywordIndexService;
        this.llmEnabled = llmEnabled;
        this.maxCacheSize = maxCacheSize;
        log.info("QueryRewriteService初始化: llm={}, maxCache={}", llmEnabled, maxCacheSize);
    }

    /**
     * 智能查询扩展主入口
     *
     * @param originalQuery 原始查询
     * @return 扩展后的查询列表（始终包含原始查询）
     */
    public List<String> rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = originalQuery.trim().replaceAll("\\s+", " ");
        Set<String> expandedQueries = new LinkedHashSet<>();
        expandedQueries.add(normalized);

        // 第1层：语料库词汇扩展（零成本）
        String corpusExpansion = expandWithCorpusVocabulary(normalized);
        if (corpusExpansion != null && !corpusExpansion.isBlank()) {
            expandedQueries.add(corpusExpansion);
            log.debug("语料库扩展: '{}'", corpusExpansion);
        }

        // 第2层：LLM智能改写（仅口语化查询 + 已启用）
        if (llmEnabled && isColloquialQuery(normalized)) {
            List<String> llmResults = llmRewrite(normalized);
            expandedQueries.addAll(llmResults);
        }

        List<String> result = new ArrayList<>(expandedQueries);
        if (result.size() > 1) {
            log.info("查询扩展: '{}' -> {}路", originalQuery, result.size());
        }
        return result;
    }

    // ==================== 第1层：语料库词汇扩展 ====================

    /**
     * 基于已索引文档的词汇做智能扩展
     *
     * 原理：从倒排索引中找到与查询相关的领域词汇，
     * 这些词汇来自实际文档，代表了"文档中怎么称呼这个概念"。
     *
     * 优化策略：
     * - 只保留在多个文档中出现的词汇（提高相关性）
     * - 按出现频率排序，取 Top 3（减少噪声）
     * - 过滤掉过短或与原查询重复的词
     */
    private String expandWithCorpusVocabulary(String query) {
        var stats = keywordIndexService.getStats();
        if (stats.docCount() < 3) {
            return null; // 索引太小，不做扩展
        }

        // 从查询中提取有意义的token
        List<String> queryTokens = extractMeaningfulTokens(query);
        if (queryTokens.isEmpty()) {
            return null;
        }

        // 从 BM25 索引中搜索相关文档，提取其 metadata 中的领域词汇
        var searchResults = keywordIndexService.search(query, 5);
        if (searchResults.isEmpty()) {
            return null;
        }

        // 统计每个领域词汇在多少个文档中出现
        Map<String, Integer> termFrequency = new LinkedHashMap<>();

        for (var result : searchResults) {
            Map<String, String> meta = result.metadata();
            if (meta == null) continue;

            Set<String> docTerms = new HashSet<>();

            // 提取标题中的领域词汇
            String title = meta.get("title");
            if (title != null && !title.isEmpty()) {
                docTerms.addAll(extractMeaningfulTokens(title));
            }

            // 提取 API 路径中的领域词汇
            String apiPath = meta.get("api_path");
            if (apiPath != null && !apiPath.isEmpty()) {
                for (String part : apiPath.split("[/\\-_]")) {
                    if (part.length() >= 3 && !part.matches("\\{.+\\}")) {
                        docTerms.add(part.toLowerCase());
                    }
                }
            }

            // 提取 HTTP 方法
            String httpMethod = meta.get("http_method");
            if (httpMethod != null && !httpMethod.isEmpty()) {
                docTerms.add(httpMethod.toUpperCase());
            }

            // 提取参数名
            String paramNames = meta.get("param_names");
            if (paramNames != null && !paramNames.isEmpty()) {
                for (String p : paramNames.split("[,，\\s]+")) {
                    if (p.length() >= 2) docTerms.add(p.toLowerCase());
                }
            }

            // 每个文档中的词汇只计数一次
            for (String term : docTerms) {
                termFrequency.merge(term, 1, Integer::sum);
            }
        }

        // 过滤掉已经在查询中出现的词、过短的、只出现一次的词
        Set<String> queryLower = queryTokens.stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        
        List<Map.Entry<String, Integer>> sortedTerms = termFrequency.entrySet().stream()
                .filter(e -> !queryLower.contains(e.getKey().toLowerCase()))
                .filter(e -> e.getKey().length() >= 2)
                .filter(e -> e.getValue() >= 2) // 至少在 2 个文档中出现
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(3) // 最多取 3 个词，减少噪声
                .collect(Collectors.toList());

        if (sortedTerms.isEmpty()) {
            return null;
        }

        String expansion = sortedTerms.stream()
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" "));
        
        log.debug("语料库扩展词: {} (频率: {})", 
                sortedTerms.stream().map(Map.Entry::getKey).collect(Collectors.joining(", ")),
                sortedTerms.stream().map(e -> String.valueOf(e.getValue())).collect(Collectors.joining(", ")));
        
        return query + " " + expansion;
    }

    /**
     * 从文本中提取有意义的token（过滤短词和纯数字）
     */
    private List<String> extractMeaningfulTokens(String text) {
        List<String> tokens = new ArrayList<>();
        // 英文/数字token
        String[] words = text.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        for (String word : words) {
            if (word.length() >= 2 && !word.matches("\\d+")) {
                tokens.add(word);
            }
        }
        // 中文2-4字组合
        String chinese = text.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length() - 1; i++) {
            if (i + 1 < chinese.length()) tokens.add(chinese.substring(i, i + 2));
            if (i + 3 < chinese.length()) tokens.add(chinese.substring(i, i + 4));
        }
        return tokens;
    }

    // ==================== 第2层：LLM智能改写 ====================

    /**
     * 判断查询是否口语化（需要LLM介入）
     */
    private boolean isColloquialQuery(String query) {
        // 包含API路径的查询已经很精确，不需要LLM
        if (query.matches(".*(?:/api/|/v[0-9]+/).*")) {
            return false;
        }
        // 包含HTTP方法关键词的不需要LLM
        if (query.matches(".*\\b(GET|POST|PUT|DELETE|PATCH)\\b.*")) {
            return false;
        }
        // 检查口语化特征词
        for (String indicator : COLLOQUIAL_INDICATORS) {
            if (query.contains(indicator)) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM改写口语化查询
     * 注入文档上下文让LLM更了解领域
     */
    private List<String> llmRewrite(String query) {
        // 检查缓存
        List<String> cached = rewriteCache.get(query);
        if (cached != null) {
            return cached;
        }

        try {
            // 收集文档上下文（标题列表）
            String context = buildDocumentContext();
            String prompt = buildRewritePrompt(query, context);
            String response = chatModel.generate(prompt);

            List<String> rewritten = parseRewriteResponse(response, query);

            // 缓存
            if (rewriteCache.size() >= maxCacheSize) {
                rewriteCache.clear();
            }
            rewriteCache.put(query, rewritten);

            log.info("LLM改写: '{}' -> {}", query, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("LLM改写失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 构建文档上下文（标题摘要），帮助LLM理解领域
     */
    private String buildDocumentContext() {
        var stats = keywordIndexService.getStats();
        if (stats.docCount() == 0) return "";

        // 从索引中获取一些代表性结果来构建上下文
        var sampleResults = keywordIndexService.search("API 接口", 10);
        Set<String> titles = new LinkedHashSet<>();
        for (var result : sampleResults) {
            if (result.metadata() != null) {
                String title = result.metadata().get("title");
                if (title != null && !title.isEmpty()) {
                    titles.add(title);
                }
            }
        }

        if (titles.isEmpty()) return "";
        return "已知文档条目: " + String.join(", ", titles);
    }

    private String buildRewritePrompt(String query, String context) {
        return """
                你是文档检索查询改写助手。将用户的口语化问题改写为适合文档检索的精确查询。
                
                规则：
                1. 提取关键实体（接口名、路径、参数名）
                2. 补充技术术语和同义词
                3. 输出1-2个改写后的查询，每行一个，不要编号
                4. 保持简洁，每个查询不超过50字
                
                %s
                
                用户查询：%s
                
                改写后的查询：""".formatted(
                context.isEmpty() ? "" : "参考信息: " + context,
                query);
    }

    private List<String> parseRewriteResponse(String response, String originalQuery) {
        if (response == null || response.isBlank()) {
            return Collections.emptyList();
        }

        List<String> queries = new ArrayList<>();
        for (String line : response.strip().split("\\n")) {
            String trimmed = line.trim()
                    .replaceAll("^[0-9]+[.、)\\]]\\s*", "")
                    .replaceAll("^[-*•]\\s*", "")
                    .trim();

            if (!trimmed.isEmpty()
                    && !trimmed.equalsIgnoreCase(originalQuery)
                    && trimmed.length() >= 2
                    && trimmed.length() <= 200) {
                queries.add(trimmed);
            }
        }
        return queries.subList(0, Math.min(queries.size(), 2));
    }

    // ==================== 管理 ====================

    public void clearCache() {
        rewriteCache.clear();
        log.info("查询改写缓存已清空");
    }

    public int getCacheSize() {
        return rewriteCache.size();
    }

    public boolean isLlmEnabled() {
        return llmEnabled;
    }
}
