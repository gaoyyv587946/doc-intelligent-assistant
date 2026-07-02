package com.example.apiagent.controller;

import com.example.apiagent.service.HybridSearchService;
import com.example.apiagent.service.HybridSearchService.SearchResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 权重调优实验接口
 *
 * 支持：
 * 1. 自定义权重组合批量实验，对比不同 vectorWeight/keywordWeight 下的检索准确率
 * 2. 输出 Recall@K、P@1、MRR、NDCG@K 指标
 * 3. 按查询类型分组统计
 *
 * 用法：
 * - POST /api/tuning/run  运行预设权重组合实验
 * - POST /api/tuning/run?customWeights=1.3/1.0,2.0/1.0  自定义权重组合
 */
@RestController
@RequestMapping("/api/tuning")
public class WeightTuningController {

    private static final Logger log = LoggerFactory.getLogger(WeightTuningController.class);

    private final HybridSearchService hybridSearchService;
    private final ObjectMapper objectMapper;

    // 预设权重组合：vectorWeight/keywordWeight
    private static final double[][] PRESET_WEIGHTS = {
            {1.0, 1.0},   // 等权基线
            {1.0, 1.5},   // 偏keyword
            {1.0, 2.0},   // 强偏keyword
            {1.3, 1.0},   // 略偏vector
            {1.5, 1.0},   // 偏vector（当前默认）
            {1.7, 1.0},   // 更偏vector
            {2.0, 1.0},   // 强偏vector
            {2.5, 1.0},   // 极强偏vector
            {1.2, 1.0},   // 微调偏vector
            {1.5, 1.2},   // 双高偏vector
            {1.3, 1.2},   // 双高均衡
    };

    public WeightTuningController(HybridSearchService hybridSearchService, ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 运行权重调优实验
     *
     * @param datasetPath   评估数据集路径（默认用扩展数据集）
     * @param k             Top-K 值
     * @param customWeights 自定义权重组合，格式 "v1/k1,v2/k2,..."，为空则使用预设
     */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> runExperiment(
            @RequestParam(value = "dataset", defaultValue = "eval-dataset-expanded.json") String datasetPath,
            @RequestParam(value = "k", defaultValue = "5") int k,
            @RequestParam(value = "customWeights", required = false) String customWeights) {

        List<EvalCase> cases = loadDataset(datasetPath);
        if (cases.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "评估数据集为空或文件不存在: " + datasetPath));
        }

        // 解析权重组合
        double[][] weightCombinations;
        if (customWeights != null && !customWeights.isBlank()) {
            weightCombinations = parseCustomWeights(customWeights);
        } else {
            weightCombinations = PRESET_WEIGHTS;
        }

        log.info("开始权重调优实验: {} 条评估数据, {} 组权重, k={}", cases.size(), weightCombinations.length, k);

        List<Map<String, Object>> experimentResults = new ArrayList<>();

        for (double[] weights : weightCombinations) {
            double vw = weights[0];
            double kw = weights[1];

            ExperimentResult result = runOneExperiment(cases, vw, kw, k);
            experimentResults.add(result.toMap(vw, kw, k));

            log.info("权重组合 v={}/k={}: Recall@{}={}, P@1={}, MRR={}",
                    vw, kw, k,
                    String.format("%.4f", result.recallAtK),
                    String.format("%.4f", result.precisionAt1),
                    String.format("%.4f", result.mrr));
        }

        // 排序：按 Recall@K 降序
        experimentResults.sort((a, b) -> {
            double ra = (double) a.get("recallAt" + k);
            double rb = (double) b.get("recallAt" + k);
            return Double.compare(rb, ra);
        });

        // 找出最优
        Map<String, Object> best = experimentResults.get(0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("datasetPath", datasetPath);
        response.put("totalCases", cases.size());
        response.put("k", k);
        response.put("weightCombinations", weightCombinations.length);
        response.put("bestCombination", best);
        response.put("allResults", experimentResults);
        response.put("recommendation", buildRecommendation(experimentResults));

        return ResponseEntity.ok(response);
    }

    /**
     * 运行单组权重实验
     */
    private ExperimentResult runOneExperiment(List<EvalCase> cases, double vw, double kw, int k) {
        List<double[]> metrics = new ArrayList<>(); // [recall, rr, ndcg, p1]
        Map<String, List<double[]>> typeMetrics = new LinkedHashMap<>();

        for (EvalCase evalCase : cases) {
            try {
                List<SearchResult> results = hybridSearchService.searchWithCustomWeights(
                        evalCase.query(), vw, kw, k);

                List<String> resultDocIds = results.stream()
                        .map(SearchResult::docId)
                        .limit(k)
                        .collect(Collectors.toList());

                Set<String> relevantSet = new HashSet<>(evalCase.relevantDocIds());
                double recall = computeRecall(resultDocIds, relevantSet);
                double rr = computeReciprocalRank(resultDocIds, relevantSet);
                double dcg = computeDCG(resultDocIds, relevantSet);
                double idealDcg = computeIdealDCG(relevantSet.size(), k);
                double ndcg = idealDcg > 0 ? dcg / idealDcg : 0.0;
                boolean p1 = !resultDocIds.isEmpty() && relevantSet.contains(resultDocIds.get(0));

                double[] m = {recall, rr, ndcg, p1 ? 1.0 : 0.0};
                metrics.add(m);

                typeMetrics.computeIfAbsent(evalCase.queryType(), x -> new ArrayList<>()).add(m);

            } catch (Exception e) {
                log.warn("评估 case 失败: query={}, error={}", evalCase.query(), e.getMessage());
                metrics.add(new double[]{0, 0, 0, 0});
                typeMetrics.computeIfAbsent(evalCase.queryType(), x -> new ArrayList<>())
                        .add(new double[]{0, 0, 0, 0});
            }
        }

        // 汇总
        double avgRecall = metrics.stream().mapToDouble(m -> m[0]).average().orElse(0);
        double avgMRR = metrics.stream().mapToDouble(m -> m[1]).average().orElse(0);
        double avgNDCG = metrics.stream().mapToDouble(m -> m[2]).average().orElse(0);
        double avgP1 = metrics.stream().mapToDouble(m -> m[3]).average().orElse(0);

        // 按类型汇总
        Map<String, Map<String, Double>> typeStats = new LinkedHashMap<>();
        for (Map.Entry<String, List<double[]>> entry : typeMetrics.entrySet()) {
            List<double[]> tm = entry.getValue();
            typeStats.put(entry.getKey(), Map.of(
                    "count", (double) tm.size(),
                    "recall", tm.stream().mapToDouble(m -> m[0]).average().orElse(0),
                    "p@1", tm.stream().mapToDouble(m -> m[3]).average().orElse(0),
                    "mrr", tm.stream().mapToDouble(m -> m[1]).average().orElse(0)
            ));
        }

        return new ExperimentResult(avgRecall, avgMRR, avgNDCG, avgP1, typeStats);
    }

    private double computeRecall(List<String> resultDocIds, Set<String> relevantSet) {
        if (relevantSet.isEmpty()) return 0.0;
        long hitCount = resultDocIds.stream().filter(relevantSet::contains).count();
        return (double) hitCount / relevantSet.size();
    }

    private double computeReciprocalRank(List<String> resultDocIds, Set<String> relevantSet) {
        for (int i = 0; i < resultDocIds.size(); i++) {
            if (relevantSet.contains(resultDocIds.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double computeDCG(List<String> resultDocIds, Set<String> relevantSet) {
        double dcg = 0.0;
        for (int i = 0; i < resultDocIds.size(); i++) {
            if (relevantSet.contains(resultDocIds.get(i))) {
                dcg += 1.0 / Math.log(i + 2);
            }
        }
        return dcg;
    }

    private double computeIdealDCG(int relevantCount, int k) {
        double idcg = 0.0;
        int n = Math.min(relevantCount, k);
        for (int i = 0; i < n; i++) {
            idcg += 1.0 / Math.log(i + 2);
        }
        return idcg;
    }

    private double[][] parseCustomWeights(String customWeights) {
        List<double[]> result = new ArrayList<>();
        for (String pair : customWeights.split(",")) {
            String[] parts = pair.trim().split("[/x]");
            if (parts.length == 2) {
                try {
                    result.add(new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])});
                } catch (NumberFormatException e) {
                    log.warn("无法解析权重组合: {}", pair);
                }
            }
        }
        return result.toArray(new double[0][]);
    }

    private List<EvalCase> loadDataset(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("评估数据集文件不存在: {}", path);
                return Collections.emptyList();
            }
            return objectMapper.readValue(is, new TypeReference<List<EvalCase>>() {});
        } catch (Exception e) {
            log.error("加载评估数据集失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String buildRecommendation(List<Map<String, Object>> results) {
        if (results.isEmpty()) return "无实验结果";
        Map<String, Object> best = results.get(0);
        return String.format(
                "推荐权重: vector=%.1f, keyword=%.1f (Recall@K=%.4f, P@1=%.4f, MRR=%.4f, NDCG=%.4f)",
                best.get("vectorWeight"), best.get("keywordWeight"),
                best.get("recallAt5") != null ? best.get("recallAt5") : best.get("recallAtK"),
                best.get("p@1"), best.get("mrr"), best.get("ndcg"));
    }

    // ========== 数据类 ==========

    public record EvalCase(String query, List<String> relevantDocIds, String queryType) {}

    private record ExperimentResult(
            double recallAtK, double mrr, double ndcg, double precisionAt1,
            Map<String, Map<String, Double>> typeStats) {

        public Map<String, Object> toMap(double vw, double kw, int k) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("vectorWeight", vw);
            m.put("keywordWeight", kw);
            m.put("ratio", String.format("%.2f:1", vw / kw));
            m.put("recallAt" + k, Math.round(recallAtK * 10000.0) / 10000.0);
            m.put("p@1", Math.round(precisionAt1 * 10000.0) / 10000.0);
            m.put("mrr", Math.round(mrr * 10000.0) / 10000.0);
            m.put("ndcg", Math.round(ndcg * 10000.0) / 10000.0);
            m.put("typeStats", typeStats);
            return m;
        }
    }
}
