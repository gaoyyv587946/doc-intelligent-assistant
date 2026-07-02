package com.example.apiagent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 混合检索服务
 *
 * 智能特性：
 * - LLM动态查询扩展（替代硬编码同义词表）
 * - 自适应RRF权重（基于两路检索分数分布动态调整）
 * - 自适应Metadata Boosting（基于metadata覆盖率动态加分）
 * - 所有权重参数可通过application.yml配置
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private final EmbeddingModel embeddingModel;
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final KeywordIndexService keywordIndexService;
    private final QueryRewriteService queryRewriteService;

    @Value("${rag.min-score:0.6}")
    private double minScore;

    @Value("${rag.max-results:5}")
    private int maxResults;

    // ===== 可配置参数 =====

    @Value("${rag.rrf.k:60}")
    private int rrfK;

    @Value("${rag.weight.exact-keyword:1.5}")
    private double weightExactKeyword;

    @Value("${rag.weight.exact-vector:1.0}")
    private double weightExactVector;

    @Value("${rag.weight.semantic-vector:1.5}")
    private double weightSemanticVector;

    @Value("${rag.weight.semantic-keyword:1.0}")
    private double weightSemanticKeyword;

    @Value("${rag.boost.title-match:1.5}")
    private double boostTitleMatch;

    @Value("${rag.boost.param-match:1.3}")
    private double boostParamMatch;

    @Value("${rag.boost.api-path-match:1.4}")
    private double boostApiPathMatch;

    @Value("${rag.adaptive-weight.enabled:true}")
    private boolean adaptiveWeightEnabled;

    // API路径模式
    private static final Pattern API_PATH_PATTERN = Pattern.compile(
            "(/(?:api|v[0-9]+)/[a-zA-Z0-9/_{\\}-]+|/[a-zA-Z0-9/_-]+/[a-zA-Z0-9/_-]+)"
    );

    // HTTP方法模式
    private static final Pattern HTTP_METHOD_PATTERN = Pattern.compile(
            "\\b(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\b",
            Pattern.CASE_INSENSITIVE
    );

    public HybridSearchService(
            EmbeddingModel embeddingModel,
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            KeywordIndexService keywordIndexService,
            QueryRewriteService queryRewriteService) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.keywordIndexService = keywordIndexService;
        this.queryRewriteService = queryRewriteService;
    }

    /**
     * 混合检索主入口（经过查询改写）
     */
    public List<SearchResult> search(String query) {
        return searchWithAnalysis(query).finalResults();
    }

    /**
     * 原始检索（不做任何查询改写，用于诊断分析和Agent直检）
     * 确保分析页面与Agent实际检索行为完全一致
     */
    public List<SearchResult> searchRaw(String query) {
        return searchRawWithAnalysis(query).finalResults();
    }

    /**
     * 带完整分析的检索（经过查询改写，供调优对比使用）
     */
    public SearchAnalysis searchWithAnalysis(String query) {
        SearchAnalysis.Builder a = new SearchAnalysis.Builder(query);
        log.info("开始混合检索: {}", query);

        // 第0步：记录配置
        a.recordConfig(rrfK, adaptiveWeightEnabled,
                Map.of("exact-vector", weightExactVector, "exact-keyword", weightExactKeyword,
                        "semantic-vector", weightSemanticVector, "semantic-keyword", weightSemanticKeyword),
                Map.of("title", boostTitleMatch, "param", boostParamMatch, "api-path", boostApiPathMatch));

        // 第1步：LLM智能查询改写
        List<String> rewrittenQueries = queryRewriteService.rewrite(query);
        a.setRewrittenQueries(rewrittenQueries);
        if (rewrittenQueries.size() > 1) {
            log.info("LLM查询改写: {} -> {} 路召回", query, rewrittenQueries.size());
        }

        // 第2步：意图分析
        QueryIntent intent = analyzeQuery(query);
        a.recordIntent(intent.getApiPath(), intent.getHttpMethod(), intent.getKeywords(),
                intent.hasStructuredConditions() ? "精确匹配" : "语义匹配");
        log.debug("查询意图: {}", intent);

        // 第3步：精确过滤
        if (intent.hasStructuredConditions()) {
            List<SearchResult> exactResults = searchByMetadata(intent);
            if (!exactResults.isEmpty()) {
                log.info("精确匹配命中 {} 条结果", exactResults.size());
                List<SearchResult> trimmed = exactResults.subList(0, Math.min(exactResults.size(), maxResults));
                a.setExactMatchResults(trimmed);
                return a.build(trimmed);
            }
        }

        // 第4步：多路召回
        List<SearchResult> allVectorResults = new ArrayList<>();
        List<SearchResult> allKeywordResults = new ArrayList<>();

        for (String q : rewrittenQueries) {
            allVectorResults.addAll(vectorSearch(q, maxResults * 2));
            allKeywordResults.addAll(keywordSearch(q, maxResults * 2));
        }

        List<SearchResult> vectorResults = deduplicateResults(allVectorResults);
        List<SearchResult> keywordResults = deduplicateResults(allKeywordResults);

        a.recordRawResults(vectorResults, keywordResults);

        // 第5步：自适应RRF融合
        double[] weights = computeAdaptiveWeights(intent, vectorResults, keywordResults);
        a.recordWeights(intent.hasStructuredConditions(), weights[0], weights[1],
                vectorResults, keywordResults);

        List<SearchResult> mergedResults = reciprocalRankFusion(
                vectorResults, keywordResults, weights[0], weights[1]);
        a.recordFusion(mergedResults);

        // 第6步：智能Metadata Boosting
        List<SearchResult> beforeBoost = new ArrayList<>(mergedResults);
        mergedResults = applyMetadataBoosting(mergedResults, query);
        a.recordBoosting(beforeBoost, mergedResults);

        // 第7步：BM25 得分解释
        List<KeywordIndexService.BM25Explanation> bm25Explain = keywordIndexService.explainBM25(query, maxResults * 2);
        a.setBm25Explain(bm25Explain);

        log.info("混合检索完成，返回 {} 条结果 (向量{}条, 关键词{}条)",
                mergedResults.size(), vectorResults.size(), keywordResults.size());
        return a.build(mergedResults);
    }

    /**
     * 原始检索（带分析数据），完全绕过查询改写
     * 使用场景：诊断分析页面、Agent直检路径
     * 与 searchWithAnalysis 的区别：不做语料库扩展和LLM改写
     */
    public SearchAnalysis searchRawWithAnalysis(String query) {
        SearchAnalysis.Builder a = new SearchAnalysis.Builder(query);
        log.info("开始原始检索（无改写）: {}", query);

        // 记录配置
        a.recordConfig(rrfK, adaptiveWeightEnabled,
                Map.of("exact-vector", weightExactVector, "exact-keyword", weightExactKeyword,
                        "semantic-vector", weightSemanticVector, "semantic-keyword", weightSemanticKeyword),
                Map.of("title", boostTitleMatch, "param", boostParamMatch, "api-path", boostApiPathMatch));
        // 无查询改写
        a.setRewrittenQueries(List.of(query));

        // 意图分析
        QueryIntent intent = analyzeQuery(query);
        a.recordIntent(intent.getApiPath(), intent.getHttpMethod(), intent.getKeywords(),
                intent.hasStructuredConditions() ? "精确匹配" : "语义匹配");

        // 精确过滤
        if (intent.hasStructuredConditions()) {
            List<SearchResult> exactResults = searchByMetadata(intent);
            if (!exactResults.isEmpty()) {
                List<SearchResult> trimmed = exactResults.subList(0, Math.min(exactResults.size(), maxResults));
                a.setExactMatchResults(trimmed);
                return a.build(trimmed);
            }
        }

        // 直接用原始query做向量+BM25检索（不做任何扩展）
        List<SearchResult> vectorResults = vectorSearch(query, maxResults * 2);
        List<SearchResult> keywordResults = keywordSearch(query, maxResults * 2);

        a.recordRawResults(vectorResults, keywordResults);

        // 自适应RRF融合
        double[] weights = computeAdaptiveWeights(intent, vectorResults, keywordResults);
        a.recordWeights(intent.hasStructuredConditions(), weights[0], weights[1],
                vectorResults, keywordResults);

        List<SearchResult> mergedResults = reciprocalRankFusion(
                vectorResults, keywordResults, weights[0], weights[1]);
        a.recordFusion(mergedResults);

        // Metadata Boosting
        List<SearchResult> beforeBoost = new ArrayList<>(mergedResults);
        mergedResults = applyMetadataBoosting(mergedResults, query);
        a.recordBoosting(beforeBoost, mergedResults);

        // BM25 得分解释
        List<KeywordIndexService.BM25Explanation> bm25Explain = keywordIndexService.explainBM25(query, maxResults * 2);
        a.setBm25Explain(bm25Explain);

        log.info("原始检索完成，返回 {} 条结果", mergedResults.size());
        return a.build(mergedResults);
    }

    // ==================== 自定义权重检索（调优用） ====================

    /**
     * 使用自定义权重进行检索（跳过查询改写和自适应权重，专用于权重调优实验）
     *
     * @param query         原始查询
     * @param vectorWeight  向量权重
     * @param keywordWeight 关键词权重
     * @param topK          返回结果数
     * @return 检索结果列表
     */
    public List<SearchResult> searchWithCustomWeights(
            String query, double vectorWeight, double keywordWeight, int topK) {
        // 直接用原始 query 做向量 + BM25 检索，不做查询改写
        List<SearchResult> vectorResults = vectorSearch(query, topK * 2);
        List<SearchResult> keywordResults = keywordSearch(query, topK * 2);

        // 直接使用传入的权重，不做自适应调整
        List<SearchResult> merged = reciprocalRankFusion(
                vectorResults, keywordResults, vectorWeight, keywordWeight);

        // 应用 Metadata Boosting
        merged = applyMetadataBoosting(merged, query);

        return merged.stream().limit(topK).toList();
    }

    // ==================== 意图分析 ====================

    private QueryIntent analyzeQuery(String query) {
        QueryIntent intent = new QueryIntent();

        Matcher pathMatcher = API_PATH_PATTERN.matcher(query);
        if (pathMatcher.find()) {
            intent.setApiPath(pathMatcher.group(1));
        }

        Matcher methodMatcher = HTTP_METHOD_PATTERN.matcher(query);
        if (methodMatcher.find()) {
            intent.setHttpMethod(methodMatcher.group(1).toUpperCase());
        }

        String cleanedQuery = query;
        if (intent.getApiPath() != null) {
            cleanedQuery = cleanedQuery.replace(intent.getApiPath(), "");
        }
        if (intent.getHttpMethod() != null) {
            cleanedQuery = cleanedQuery.replaceAll("(?i)" + intent.getHttpMethod(), "");
        }
        intent.setKeywords(cleanedQuery.trim());

        return intent;
    }

    // ==================== 自适应权重 ====================

    /**
     * 智能计算RRF权重
     *
     * 策略：
     * 1. 基于查询类型的基础权重（精确查询偏向keyword，语义查询偏向vector）
     * 2. 基于检索分数分布的自适应调整（归一化后比较，而非直接比原始分数）
     */
    private double[] computeAdaptiveWeights(QueryIntent intent,
                                             List<SearchResult> vectorResults,
                                             List<SearchResult> keywordResults) {
        double vectorWeight;
        double keywordWeight;

        if (intent.hasStructuredConditions()) {
            vectorWeight = weightExactVector;
            keywordWeight = weightExactKeyword;
        } else {
            vectorWeight = weightSemanticVector;
            keywordWeight = weightSemanticKeyword;
        }

        if (adaptiveWeightEnabled && !vectorResults.isEmpty() && !keywordResults.isEmpty()) {
            // 归一化：vector cosine [0,1]，BM25 [0,∞) -> 都映射到 [0,1]
            double maxVectorScore = vectorResults.stream()
                    .mapToDouble(SearchResult::score).max().orElse(1);
            double maxKeywordScore = keywordResults.stream()
                    .mapToDouble(SearchResult::score).max().orElse(1);

            double avgVectorNorm = vectorResults.stream()
                    .mapToDouble(r -> r.score() / Math.max(maxVectorScore, 0.001))
                    .average().orElse(0);
            double avgKeywordNorm = keywordResults.stream()
                    .mapToDouble(r -> r.score() / Math.max(maxKeywordScore, 0.001))
                    .average().orElse(0);

            if (avgVectorNorm > 0 && avgKeywordNorm > 0) {
                double ratio = avgVectorNorm / (avgVectorNorm + avgKeywordNorm);
                double vectorFactor = 0.7 + ratio * 0.6;  // [0.7, 1.3]
                double keywordFactor = 2.0 - vectorFactor; // [0.7, 1.3]

                vectorWeight *= vectorFactor;
                keywordWeight *= keywordFactor;
                log.debug("自适应权重调整: ratio={}, vectorFactor={}, keywordFactor={}",
                        String.format("%.3f", ratio),
                        String.format("%.3f", vectorFactor),
                        String.format("%.3f", keywordFactor));
            }
        }

        return new double[]{vectorWeight, keywordWeight};
    }

    // ==================== 检索 ====================

    private List<SearchResult> searchByMetadata(QueryIntent intent) {
        Map<String, String> filters = new HashMap<>();
        if (intent.getApiPath() != null) {
            filters.put("api_path", intent.getApiPath());
        }
        if (intent.getHttpMethod() != null) {
            filters.put("http_method", intent.getHttpMethod());
        }
        return keywordIndexService.searchByMetadata(filters).stream()
                .map(kr -> new SearchResult(kr.docId(), kr.content(), kr.metadata(), kr.score(), "metadata"))
                .collect(Collectors.toList());
    }

    private List<SearchResult> vectorSearch(String query, int topK) {
        try {
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, topK, minScore);
            return matches.stream()
                    .map(match -> new SearchResult(
                            match.embedded().metadata().getString("file_name"),
                            match.embedded().text(),
                            metadataToMap(match.embedded().metadata()),
                            match.score(),
                            "vector"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<SearchResult> keywordSearch(String query, int topK) {
        return keywordIndexService.search(query, topK).stream()
                .map(kr -> {
                    // 归一化 docId：BM25 的 docId 是 "fileName#chunkIndex"，
                    // 向量检索的 docId 是 metadata("file_name")，必须统一才能在 RRF 中合并
                    String normalizedDocId = kr.metadata() != null && kr.metadata().get("file_name") != null
                            ? kr.metadata().get("file_name") : kr.docId();
                    return new SearchResult(normalizedDocId, kr.content(), kr.metadata(), kr.score(), "keyword");
                })
                .collect(Collectors.toList());
    }

    // ==================== RRF融合 ====================

    private List<SearchResult> reciprocalRankFusion(
            List<SearchResult> vectorResults,
            List<SearchResult> keywordResults,
            double vectorWeight,
            double keywordWeight) {

        Map<String, Double> docScores = new HashMap<>();
        Map<String, SearchResult> docResults = new HashMap<>();

        for (int i = 0; i < vectorResults.size(); i++) {
            SearchResult result = vectorResults.get(i);
            docScores.merge(result.docId(), vectorWeight / (rrfK + i + 1), Double::sum);
            docResults.putIfAbsent(result.docId(), result);
        }

        for (int i = 0; i < keywordResults.size(); i++) {
            SearchResult result = keywordResults.get(i);
            docScores.merge(result.docId(), keywordWeight / (rrfK + i + 1), Double::sum);
            docResults.putIfAbsent(result.docId(), result);
        }

        return docScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxResults)
                .map(e -> {
                    SearchResult original = docResults.get(e.getKey());
                    return new SearchResult(
                            original.docId(), original.content(), original.metadata(),
                            e.getValue(), original.source() + "+rrf");
                })
                .collect(Collectors.toList());
    }

    // ==================== 智能Metadata Boosting ====================

    /**
     * 自适应Metadata Boosting
     *
     * 不再使用硬编码阈值判断"是否匹配"，而是计算查询词与metadata字段的
     * 字符覆盖率（overlap ratio），覆盖率越高加分越多。
     */
    private List<SearchResult> applyMetadataBoosting(List<SearchResult> results, String query) {
        String lowerQuery = query.toLowerCase();
        Set<String> queryTokens = extractQueryTokens(lowerQuery);

        return results.stream()
                .map(result -> {
                    Map<String, String> meta = result.metadata();
                    if (meta == null) return result;

                    double boost = computeBoostScore(meta, queryTokens, lowerQuery);
                    if (boost > 1.0) {
                        log.debug("Metadata Boosting: docId={}, boost={}", result.docId(), String.format("%.2f", boost));
                        return new SearchResult(
                                result.docId(), result.content(), result.metadata(),
                                result.score() * boost, result.source() + "+boost");
                    }
                    return result;
                })
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(maxResults)
                .collect(Collectors.toList());
    }

    /**
     * 从查询中提取有意义的token（用于metadata匹配计算）
     */
    private Set<String> extractQueryTokens(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        // 英文/数字词
        String[] words = query.split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        for (String word : words) {
            if (word.length() >= 2) {
                tokens.add(word.toLowerCase());
            }
        }
        // 中文2-4字组合
        String chinese = query.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i < chinese.length(); i++) {
            if (i + 1 < chinese.length()) tokens.add(chinese.substring(i, i + 2));
            if (i + 2 < chinese.length()) tokens.add(chinese.substring(i, i + 3));
        }
        return tokens;
    }

    /**
     * 计算metadata boosting分数
     * 基于token覆盖率，而非简单的"是否包含"
     */
    private double computeBoostScore(Map<String, String> meta, Set<String> queryTokens, String lowerQuery) {
        double boost = 1.0;

        // 标题匹配：计算查询token与标题的覆盖率
        String title = meta.get("title");
        if (title != null && !title.isEmpty()) {
            double titleOverlap = computeOverlap(queryTokens, title.toLowerCase());
            if (titleOverlap > 0.5) {
                boost = Math.max(boost, 1.0 + (boostTitleMatch - 1.0) * titleOverlap);
            }
        }

        // 参数名匹配：计算查询中有多少比例是参数名
        String paramNames = meta.get("param_names");
        if (paramNames != null && !paramNames.isEmpty()) {
            Set<String> paramSet = new HashSet<>();
            for (String p : paramNames.split("[,，\\s]+")) {
                if (p.length() >= 2) paramSet.add(p.toLowerCase());
            }
            long matchedParams = paramSet.stream().filter(lowerQuery::contains).count();
            if (matchedParams > 0) {
                double paramCoverage = (double) matchedParams / paramSet.size();
                boost = Math.max(boost, 1.0 + (boostParamMatch - 1.0) * Math.min(paramCoverage * 2, 1.0));
            }
        }

        // API路径匹配：计算查询词与路径段的覆盖率
        String apiPath = meta.get("api_path");
        if (apiPath != null && !apiPath.isEmpty()) {
            Set<String> pathTokens = new HashSet<>();
            for (String part : apiPath.split("[/\\-_]")) {
                if (part.length() >= 3) pathTokens.add(part.toLowerCase());
            }
            long matchedPathTokens = pathTokens.stream().filter(lowerQuery::contains).count();
            if (matchedPathTokens > 0 && !pathTokens.isEmpty()) {
                double pathCoverage = (double) matchedPathTokens / pathTokens.size();
                boost = Math.max(boost, 1.0 + (boostApiPathMatch - 1.0) * pathCoverage);
            }
        }

        return boost;
    }

    /**
     * 计算两个文本的token覆盖率
     */
    private double computeOverlap(Set<String> tokens, String target) {
        if (tokens.isEmpty() || target.isEmpty()) return 0;
        long matched = tokens.stream().filter(target::contains).count();
        return (double) matched / tokens.size();
    }

    // ==================== 工具方法 ====================

    private List<SearchResult> deduplicateResults(List<SearchResult> results) {
        Map<String, SearchResult> deduplicated = new LinkedHashMap<>();
        for (SearchResult result : results) {
            SearchResult existing = deduplicated.get(result.docId());
            if (existing == null || result.score() > existing.score()) {
                deduplicated.put(result.docId(), result);
            }
        }
        return new ArrayList<>(deduplicated.values());
    }

    private Map<String, String> metadataToMap(dev.langchain4j.data.document.Metadata metadata) {
        Map<String, String> map = new HashMap<>();
        metadata.toMap().forEach((k, v) -> map.put(k, String.valueOf(v)));
        return map;
    }

    // ========== 内部数据类 ==========

    private static class QueryIntent {
        private String apiPath;
        private String httpMethod;
        private String keywords;

        public String getApiPath() { return apiPath; }
        public void setApiPath(String apiPath) { this.apiPath = apiPath; }
        public String getHttpMethod() { return httpMethod; }
        public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
        public String getKeywords() { return keywords; }
        public void setKeywords(String keywords) { this.keywords = keywords; }
        public boolean hasStructuredConditions() { return apiPath != null || httpMethod != null; }

        @Override
        public String toString() {
            return "QueryIntent{apiPath='" + apiPath + "', httpMethod='" + httpMethod + "', keywords='" + keywords + "'}";
        }
    }

    public record SearchResult(
            String docId,
            String content,
            Map<String, String> metadata,
            double score,
            String source
    ) {}

    // ==================== 检索分析数据模型 ====================

    /**
     * 每条结果在各阶段的详细分数
     */
    public record RankedDetail(
            String docId,
            String title,
            String contentPreview,
            // 原始检索
            Integer vectorRank,       // 向量检索排名（null表示未召回）
            Double vectorRawScore,    // 余弦相似度 [0,1]
            Integer keywordRank,      // BM25检索排名
            Double keywordRawScore,   // BM25分数 [0,∞)
            // RRF融合
            Double vectorRRF,         // vectorWeight / (K + vectorRank + 1)
            Double keywordRRF,        // keywordWeight / (K + keywordRank + 1)
            Double rrfTotal,          // vectorRRF + keywordRRF
            // Metadata Boosting
            Double boostFactor,       // 1.0 = 无加分
            Double finalScore         // rrfTotal * boostFactor
    ) {}

    /**
     * 完整检索分析结果
     */
    public record SearchAnalysis(
            String query,
            List<String> rewrittenQueries,
            Map<String, Object> intent,
            Map<String, Object> config,
            List<RankedDetail> vectorRanked,
            List<RankedDetail> keywordRanked,
            Map<String, Object> weightAnalysis,
            List<RankedDetail> fusionRanked,
            List<RankedDetail> boostedRanked,
            List<SearchResult> finalResults,
            List<KeywordIndexService.BM25Explanation> bm25Explain
    ) {
        public static class Builder {
            private final String query;
            private List<String> rewrittenQueries = List.of();
            private Map<String, Object> intent = Map.of();
            private Map<String, Object> config = Map.of();
            private List<SearchResult> rawVector = List.of();
            private List<SearchResult> rawKeyword = List.of();
            private Map<String, Object> weightAnalysis = Map.of();
            private List<SearchResult> fusionResults = List.of();
            private List<SearchResult> boostedResults = List.of();
            private List<SearchResult> exactResults = List.of();
            private List<KeywordIndexService.BM25Explanation> bm25Explain = List.of();

            public Builder(String query) { this.query = query; }

            public void setBm25Explain(List<KeywordIndexService.BM25Explanation> e) { this.bm25Explain = e; }

            public void setRewrittenQueries(List<String> q) { this.rewrittenQueries = q; }

            public void recordConfig(int rrfK, boolean adaptive,
                                      Map<String, Object> weights, Map<String, Object> boosts) {
                this.config = Map.of(
                        "rrfK", rrfK, "adaptiveEnabled", adaptive,
                        "baseWeights", weights, "boostLimits", boosts);
            }

            public void recordIntent(String apiPath, String httpMethod,
                                      String keywords, String type) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", type);
                m.put("apiPath", apiPath != null ? apiPath : "无");
                m.put("httpMethod", httpMethod != null ? httpMethod : "无");
                m.put("keywords", keywords);
                this.intent = m;
            }

            public void recordRawResults(List<SearchResult> v, List<SearchResult> k) {
                this.rawVector = v;
                this.rawKeyword = k;
            }

            public void recordWeights(boolean isExact, double finalVectorWeight,
                                       double finalKeywordWeight,
                                       List<SearchResult> vResults, List<SearchResult> kResults) {
                double avgV = vResults.stream().mapToDouble(SearchResult::score).average().orElse(0);
                double avgK = kResults.stream().mapToDouble(SearchResult::score).average().orElse(0);
                double maxV = vResults.stream().mapToDouble(SearchResult::score).max().orElse(0);
                double maxK = kResults.stream().mapToDouble(SearchResult::score).max().orElse(0);

                double avgVNorm = maxV > 0 ? avgV / maxV : 0;
                double avgKNorm = maxK > 0 ? avgK / maxK : 0;
                double ratio = (avgVNorm + avgKNorm) > 0
                        ? avgVNorm / (avgVNorm + avgKNorm) : 0.5;

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("baseVectorWeight", isExact ? 1.0 : 1.5);
                m.put("baseKeywordWeight", isExact ? 1.5 : 1.0);
                m.put("adaptiveEnabled", true);
                m.put("avgVectorScoreRaw", avgV);
                m.put("avgKeywordScoreRaw", avgK);
                m.put("maxVectorScore", maxV);
                m.put("maxKeywordScore", maxK);
                m.put("avgVectorScoreNormalized", avgVNorm);
                m.put("avgKeywordScoreNormalized", avgKNorm);
                m.put("ratio", ratio);
                m.put("vectorFactor", 0.7 + ratio * 0.6);
                m.put("keywordFactor", 2.0 - (0.7 + ratio * 0.6));
                m.put("finalVectorWeight", finalVectorWeight);
                m.put("finalKeywordWeight", finalKeywordWeight);
                this.weightAnalysis = m;
            }

            public void recordFusion(List<SearchResult> r) { this.fusionResults = r; }
            public void recordBoosting(List<SearchResult> before, List<SearchResult> after) {
                this.boostedResults = after;
            }
            public void setExactMatchResults(List<SearchResult> r) { this.exactResults = r; }

            public SearchAnalysis build(List<SearchResult> finalResults) {
                // 如果精确匹配直接返回
                if (!exactResults.isEmpty()) {
                    return new SearchAnalysis(query, rewrittenQueries, intent, config,
                            List.of(), List.of(), Map.of("note", "精确匹配，未经RRF融合"),
                            List.of(), List.of(), exactResults, bm25Explain);
                }

                // 构建 vectorRanked 和 keywordRanked
                List<RankedDetail> vRanked = new ArrayList<>();
                for (int i = 0; i < rawVector.size(); i++) {
                    SearchResult r = rawVector.get(i);
                    vRanked.add(new RankedDetail(
                            r.docId(), getTitle(r), preview(r.content()),
                            i + 1, r.score(), null, null,
                            null, null, null, null, null));
                }

                List<RankedDetail> kRanked = new ArrayList<>();
                for (int i = 0; i < rawKeyword.size(); i++) {
                    SearchResult r = rawKeyword.get(i);
                    kRanked.add(new RankedDetail(
                            r.docId(), getTitle(r), preview(r.content()),
                            null, null, i + 1, r.score(),
                            null, null, null, null, null));
                }

                // 构建 fusionRanked: 每篇文档的 RRF 各分项
                int rrfK = (int) config.getOrDefault("rrfK", 60);
                double vw = (double) weightAnalysis.getOrDefault("finalVectorWeight", 1.0);
                double kw = (double) weightAnalysis.getOrDefault("finalKeywordWeight", 1.0);

                Map<String, int[]> docVectorRank = new HashMap<>();
                for (int i = 0; i < rawVector.size(); i++)
                    docVectorRank.put(rawVector.get(i).docId(), new int[]{i + 1});
                Map<String, int[]> docKeywordRank = new HashMap<>();
                for (int i = 0; i < rawKeyword.size(); i++)
                    docKeywordRank.put(rawKeyword.get(i).docId(), new int[]{i + 1});

                List<RankedDetail> fRanked = new ArrayList<>();
                for (SearchResult r : fusionResults) {
                    int vRank = docVectorRank.containsKey(r.docId()) ? docVectorRank.get(r.docId())[0] : -1;
                    int kRank = docKeywordRank.containsKey(r.docId()) ? docKeywordRank.get(r.docId())[0] : -1;
                    double vRRF = vRank > 0 ? vw / (rrfK + vRank) : 0;
                    double kRRF = kRank > 0 ? kw / (rrfK + kRank) : 0;
                    fRanked.add(new RankedDetail(
                            r.docId(), getTitle(r), preview(r.content()),
                            vRank > 0 ? vRank : null,
                            vRank > 0 ? rawVector.stream().filter(x -> x.docId().equals(r.docId())).findFirst().map(SearchResult::score).orElse(null) : null,
                            kRank > 0 ? kRank : null,
                            kRank > 0 ? rawKeyword.stream().filter(x -> x.docId().equals(r.docId())).findFirst().map(SearchResult::score).orElse(null) : null,
                            vRRF, kRRF, r.score(), null, null));
                }

                // 构建 boostedRanked
                List<RankedDetail> bRanked = new ArrayList<>();
                Map<String, Double> fusionScoreMap = new HashMap<>();
                for (SearchResult r : fusionResults)
                    fusionScoreMap.put(r.docId(), r.score());
                for (SearchResult r : boostedResults) {
                    Double beforeBoost = fusionScoreMap.get(r.docId());
                    double boost = (beforeBoost != null && beforeBoost > 0) ? r.score() / beforeBoost : 1.0;
                    RankedDetail fusionDetail = fRanked.stream()
                            .filter(d -> d.docId().equals(r.docId())).findFirst().orElse(null);
                    bRanked.add(new RankedDetail(
                            r.docId(), getTitle(r), preview(r.content()),
                            fusionDetail != null ? fusionDetail.vectorRank() : null,
                            fusionDetail != null ? fusionDetail.vectorRawScore() : null,
                            fusionDetail != null ? fusionDetail.keywordRank() : null,
                            fusionDetail != null ? fusionDetail.keywordRawScore() : null,
                            fusionDetail != null ? fusionDetail.vectorRRF() : null,
                            fusionDetail != null ? fusionDetail.keywordRRF() : null,
                            fusionDetail != null ? fusionDetail.rrfTotal() : null,
                            boost, r.score()));
                }

                return new SearchAnalysis(query, rewrittenQueries, intent, config,
                        vRanked, kRanked, weightAnalysis, fRanked, bRanked, finalResults, bm25Explain);
            }

            private static String getTitle(SearchResult r) {
                if (r.metadata() != null && r.metadata().get("title") != null) return r.metadata().get("title");
                return r.docId();
            }

            private static String preview(String content) {
                if (content == null) return "";
                return content.length() > 80 ? content.substring(0, 80) + "..." : content;
            }
        }
    }
}
