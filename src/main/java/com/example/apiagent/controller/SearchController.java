package com.example.apiagent.controller;

import com.example.apiagent.service.HybridSearchService;
import com.example.apiagent.service.HybridSearchService.RankedDetail;
import com.example.apiagent.service.HybridSearchService.SearchAnalysis;
import com.example.apiagent.service.KeywordIndexService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索分析接口（调优工具）
 * 提供 RRF 融合全过程的详细分数数据
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final HybridSearchService hybridSearchService;
    private final KeywordIndexService keywordIndexService;

    public SearchController(HybridSearchService hybridSearchService,
                            KeywordIndexService keywordIndexService) {
        this.hybridSearchService = hybridSearchService;
        this.keywordIndexService = keywordIndexService;
    }

    /**
     * 检索分析：返回完整的 RRF 融合过程数据
     *
     * GET /api/search/analyze?q=查询内容
     */
    @GetMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "查询内容不能为空"));
        }

        SearchAnalysis analysis = hybridSearchService.searchWithAnalysis(query);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("query", analysis.query());
        response.put("rewrittenQueries", analysis.rewrittenQueries());
        response.put("intent", analysis.intent());
        response.put("config", analysis.config());
        response.put("weightAnalysis", analysis.weightAnalysis());

        // 向量召回结果
        response.put("vectorResults", analysis.vectorRanked().stream()
                .map(d -> formatRanked(d, "vector")).collect(Collectors.toList()));

        // BM25召回结果
        response.put("keywordResults", analysis.keywordRanked().stream()
                .map(d -> formatRanked(d, "keyword")).collect(Collectors.toList()));

        // RRF融合详细
        response.put("fusionResults", analysis.fusionRanked().stream()
                .map(d -> formatFusion(d)).collect(Collectors.toList()));

        // Metadata Boosting 结果
        response.put("boostedResults", analysis.boostedRanked().stream()
                .map(d -> formatBoosted(d)).collect(Collectors.toList()));

        // 最终结果（简化）
        response.put("finalResults", analysis.finalResults().stream()
                .map(r -> Map.of(
                        "docId", r.docId(),
                        "score", round6(r.score()),
                        "source", r.source(),
                        "preview", r.content() != null && r.content().length() > 100
                                ? r.content().substring(0, 100) + "..." : r.content() != null ? r.content() : ""))
                .collect(Collectors.toList()));

        // BM25 得分解释
        response.put("bm25Explain", analysis.bm25Explain().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("docId", e.docId());
                    m.put("totalScore", round6(e.totalScore()));
                    m.put("luceneScore", round6(e.luceneScore()));
                    m.put("bm25CalculatedScore", round6(e.bm25CalculatedScore()));
                    m.put("docLength", e.docLength());
                    m.put("avgDocLength", Math.round(e.avgDocLength() * 100.0) / 100.0);
                    m.put("totalDocs", e.totalDocs());
                    m.put("terms", e.terms().stream()
                            .map(t -> {
                                Map<String, Object> tm = new LinkedHashMap<>();
                                tm.put("term", t.term());
                                tm.put("docFreq", t.docFreq());
                                tm.put("idf", round6(t.idf()));
                                tm.put("tf", t.tf());
                                tm.put("tfNorm", round6(t.tfNorm()));
                                tm.put("contribution", round6(t.contribution()));
                                return tm;
                            }).collect(Collectors.toList()));
                    return m;
                }).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    /**
     * BM25 调参接口：用自定义 k1/b 重新跑 BM25，对比结果变化
     *
     * GET /api/search/tune?q=查询内容&k1=1.5&b=0.4
     */
    @GetMapping("/tune")
    public ResponseEntity<Map<String, Object>> tune(
            @RequestParam("q") String query,
            @RequestParam(value = "k1", defaultValue = "1.5") float k1,
            @RequestParam(value = "b", defaultValue = "0.4") float b) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "查询内容不能为空"));
        }

        // 用自定义参数跑 BM25
        List<KeywordIndexService.SearchResult> tunedResults =
                keywordIndexService.searchWithParams(query, 10, k1, b);
        // 用默认参数跑 BM25 作为对比基准
        List<KeywordIndexService.SearchResult> defaultResults =
                keywordIndexService.search(query, 10);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("query", query);
        response.put("k1", k1);
        response.put("b", b);

        response.put("tunedResults", tunedResults.stream()
                .map(r -> {
                    String fileName = r.metadata() != null && r.metadata().get("file_name") != null
                            ? r.metadata().get("file_name") : r.docId();
                    return Map.of(
                            "docId", fileName,
                            "score", round6(r.score()),
                            "preview", r.content() != null && r.content().length() > 80
                                    ? r.content().substring(0, 80) + "..." : r.content() != null ? r.content() : "");
                }).collect(Collectors.toList()));

        response.put("defaultResults", defaultResults.stream()
                .map(r -> {
                    String fileName = r.metadata() != null && r.metadata().get("file_name") != null
                            ? r.metadata().get("file_name") : r.docId();
                    return Map.of(
                            "docId", fileName,
                            "score", round6(r.score()));
                }).collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    private Map<String, Object> formatRanked(RankedDetail d, String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", d.docId());
        m.put("title", d.title());
        m.put("preview", d.contentPreview());
        if ("vector".equals(type)) {
            m.put("rank", d.vectorRank());
            m.put("cosineScore", round6(d.vectorRawScore()));
        } else {
            m.put("rank", d.keywordRank());
            m.put("bm25Score", round6(d.keywordRawScore()));
        }
        return m;
    }

    private Map<String, Object> formatFusion(RankedDetail d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", d.docId());
        m.put("title", d.title());
        m.put("vectorRank", d.vectorRank());
        m.put("keywordRank", d.keywordRank());
        m.put("vectorRRF", round6(d.vectorRRF()));
        m.put("keywordRRF", round6(d.keywordRRF()));
        m.put("rrfTotal", round6(d.rrfTotal()));
        return m;
    }

    private Map<String, Object> formatBoosted(RankedDetail d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("docId", d.docId());
        m.put("title", d.title());
        m.put("rrfTotal", round6(d.rrfTotal()));
        m.put("boostFactor", round4(d.boostFactor()));
        m.put("finalScore", round6(d.finalScore()));
        return m;
    }

    private static double round6(Double v) {
        if (v == null) return 0;
        return Math.round(v * 1000000.0) / 1000000.0;
    }

    private static double round4(Double v) {
        if (v == null) return 1.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
