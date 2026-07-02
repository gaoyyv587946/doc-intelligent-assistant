package com.example.apiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 检索质量评估服务
 *
 * 功能：基于标注数据集评估检索系统的准确率
 * 指标：Recall@K, MRR, NDCG@K, P@1
 *
 * 使用方式：
 * 1. 准备评估数据集 eval-dataset.json（放在 classpath 下）
 * 2. 调用 POST /api/admin/eval 触发评估
 * 3. 查看评估报告，对比优化前后的指标变化
 */
@Service
public class RetrievalEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvaluator.class);

    private final HybridSearchService hybridSearchService;
    private final ObjectMapper objectMapper;

    public RetrievalEvaluator(HybridSearchService hybridSearchService, ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行评估
     *
     * @param datasetPath 评估数据集路径（classpath资源）
     * @param k           Top-K评估的K值
     * @return 评估报告
     */
    public EvalReport evaluate(String datasetPath, int k) {
        log.info("开始检索质量评估, dataset={}, k={}", datasetPath, k);

        List<EvalCase> cases = loadDataset(datasetPath);
        if (cases.isEmpty()) {
            log.warn("评估数据集为空");
            return EvalReport.empty();
        }

        List<CaseResult> caseResults = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            CaseResult result = evaluateOneCase(evalCase, k);
            caseResults.add(result);
        }

        EvalReport report = buildReport(caseResults, k);
        log.info("评估完成: {}", report);
        return report;
    }

    /**
     * 使用内置默认数据集评估
     */
    public EvalReport evaluateWithDefaultDataset(int k) {
        return evaluate("eval-dataset.json", k);
    }

    /**
     * 评估单条case
     */
    private CaseResult evaluateOneCase(EvalCase evalCase, int k) {
        try {
            List<HybridSearchService.SearchResult> results = hybridSearchService.search(evalCase.query());

            // 提取结果的docId列表
            List<String> resultDocIds = results.stream()
                    .map(HybridSearchService.SearchResult::docId)
                    .limit(k)
                    .collect(Collectors.toList());

            // 计算各指标
            Set<String> relevantSet = new HashSet<>(evalCase.relevantDocIds());
            double recall = computeRecall(resultDocIds, relevantSet);
            double rr = computeReciprocalRank(resultDocIds, relevantSet);
            double dcg = computeDCG(resultDocIds, relevantSet);
            double idealDcg = computeIdealDCG(relevantSet.size(), k);
            double ndcg = idealDcg > 0 ? dcg / idealDcg : 0.0;
            boolean p1 = !resultDocIds.isEmpty() && relevantSet.contains(resultDocIds.get(0));

            return new CaseResult(evalCase.query(), evalCase.queryType(),
                    recall, rr, ndcg, p1, resultDocIds.size());

        } catch (Exception e) {
            log.warn("评估case失败: query={}, error={}", evalCase.query(), e.getMessage());
            return new CaseResult(evalCase.query(), evalCase.queryType(), 0, 0, 0, false, 0);
        }
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
                dcg += 1.0 / Math.log(i + 2); // log2(i+2) = ln(i+2)/ln(2), 但这里用自然对数简化
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

    /**
     * 构建评估报告
     */
    private EvalReport buildReport(List<CaseResult> caseResults, int k) {
        int total = caseResults.size();
        if (total == 0) return EvalReport.empty();

        double avgRecall = caseResults.stream().mapToDouble(CaseResult::recall).average().orElse(0);
        double avgMRR = caseResults.stream().mapToDouble(CaseResult::reciprocalRank).average().orElse(0);
        double avgNDCG = caseResults.stream().mapToDouble(CaseResult::ndcg).average().orElse(0);
        double avgP1 = caseResults.stream().filter(CaseResult::precisionAtOne).count() / (double) total;

        // 按查询类型分组统计
        Map<String, TypeStats> typeStatsMap = new LinkedHashMap<>();
        caseResults.stream()
                .collect(Collectors.groupingBy(CaseResult::queryType))
                .forEach((type, results) -> {
                    int count = results.size();
                    double typeRecall = results.stream().mapToDouble(CaseResult::recall).average().orElse(0);
                    double typeMRR = results.stream().mapToDouble(CaseResult::reciprocalRank).average().orElse(0);
                    double typeNDCG = results.stream().mapToDouble(CaseResult::ndcg).average().orElse(0);
                    double typeP1 = results.stream().filter(CaseResult::precisionAtOne).count() / (double) count;
                    typeStatsMap.put(type, new TypeStats(count, typeRecall, typeMRR, typeNDCG, typeP1));
                });

        return new EvalReport(total, k, avgRecall, avgMRR, avgNDCG, avgP1, typeStatsMap);
    }

    /**
     * 加载评估数据集
     */
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

    // ========== 数据类 ==========

    /**
     * 评估case
     */
    public record EvalCase(
            String query,
            List<String> relevantDocIds,
            String queryType  // exact_path, semantic, param, colloquial, overview
    ) {}

    /**
     * 单条评估结果
     */
    public record CaseResult(
            String query,
            String queryType,
            double recall,
            double reciprocalRank,
            double ndcg,
            boolean precisionAtOne,
            int resultCount
    ) {}

    /**
     * 按查询类型的统计
     */
    public record TypeStats(
            int count,
            double recall,
            double mrr,
            double ndcg,
            double p1
    ) {}

    /**
     * 评估报告
     */
    public record EvalReport(
            int totalCases,
            int k,
            double recallAtK,
            double mrr,
            double ndcgAtK,
            double precisionAtOne,
            Map<String, TypeStats> typeStats
    ) {
        public static EvalReport empty() {
            return new EvalReport(0, 0, 0, 0, 0, 0, Map.of());
        }

        @Override
        public String toString() {
            return String.format(
                    "EvalReport{cases=%d, k=%d, Recall@%d=%.4f, MRR=%.4f, NDCG@%d=%.4f, P@1=%.4f}",
                    totalCases, k, k, recallAtK, mrr, k, ndcgAtK, precisionAtOne
            );
        }
    }
}
