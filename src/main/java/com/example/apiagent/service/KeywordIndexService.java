package com.example.apiagent.service;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.core.LowerCaseFilter;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.QueryVisitor;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

/**
 * 关键词索引服务（基于 Apache Lucene）
 *
 * 使用 Lucene 引擎替代手写倒排索引 + BM25 实现：
 * - 内置 BM25 评分（k1=1.5, b=0.4）
 * - SmartChineseAnalyzer 智能中文分词（HMM 词性标注）
 * - 可选 jieba 分词器
 * - FSDirectory 磁盘持久化索引，重启后自动恢复（无需重建）
 * - 近实时搜索（NRT），写入即可搜
 * - 支持 metadata 过滤 + 全文检索组合查询
 */
@Service
public class KeywordIndexService {

    private static final Logger log = LoggerFactory.getLogger(KeywordIndexService.class);

    // ===== Lucene 核心组件 =====
    private final Directory directory;
    private final Analyzer analyzer;
    private final IndexWriter indexWriter;
    private volatile DirectoryReader directoryReader;

    /** BM25 参数（短文档优化） */
    private static final float BM25_K1 = 1.5f;
    private static final float BM25_B = 0.4f;

    /** 文档元数据缓存（用于 metadata 过滤和统计） */
    private final ConcurrentHashMap<String, Map<String, String>> metadataCache = new ConcurrentHashMap<>();

    /** 文档内容缓存 */
    private final ConcurrentHashMap<String, String> contentCache = new ConcurrentHashMap<>();

    /** Lucene 字段名常量 */
    private static final String FIELD_DOC_ID = "docId";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_FILE_NAME = "file_name";
    private static final String FIELD_TITLE = "title";
    private static final String FIELD_API_PATH = "api_path";
    private static final String FIELD_HTTP_METHOD = "http_method";
    private static final String FIELD_TAGS = "tags";
    private static final String FIELD_SOURCE_TYPE = "source_type";

    private final String tokenizerType;
    private final String indexPath;

    public KeywordIndexService(
            @Value("${tokenizer.type:smartcn}") String tokenizerType,
            @Value("${keyword.index.path:keyword-index}") String indexPath) throws IOException {
        this.tokenizerType = tokenizerType;
        this.indexPath = indexPath;

        // 1. 创建磁盘持久化目录（重启不丢失）
        Path indexDir = Path.of(indexPath);
        Files.createDirectories(indexDir);
        // 清理崩溃后可能残留的锁文件
        Path lockFile = indexDir.resolve("write.lock");
        if (Files.exists(lockFile)) {
            Files.delete(lockFile);
            log.info("已清理残留的 Lucene 锁文件");
        }
        this.directory = FSDirectory.open(indexDir);

        // 2. 选择分词器
        this.analyzer = createAnalyzer(tokenizerType);

        // 3. 配置 IndexWriter（使用自定义 BM25 相似度）
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setSimilarity(new BM25Similarity(BM25_K1, BM25_B));
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.indexWriter = new IndexWriter(directory, config);

        // 4. 初始化 reader
        this.directoryReader = DirectoryReader.open(indexWriter);

        // 5. 从持久化索引恢复内存缓存（重启后 BM25 立即可用）
        loadCacheFromIndex();

        log.info("Lucene 关键词索引服务初始化完成，分词器: {}, 索引路径: {}, 已有文档: {}, BM25(k1={}, b={})",
                tokenizerType, indexPath, directoryReader.numDocs(), BM25_K1, BM25_B);
    }

    /**
     * 根据配置创建分词器
     */
    private Analyzer createAnalyzer(String type) {
        if ("jieba".equalsIgnoreCase(type)) {
            log.info("使用 jieba 分词器");
            return createJiebaAnalyzer();
        }
        // 默认：SmartChineseAnalyzer（HMM 中文分词 + 英文标准分词）
        log.info("使用 SmartChineseAnalyzer 智能中文分词器");

        // PerFieldAnalyzerWrapper: content 字段用 SmartChinese，metadata 字段用 Standard
        Map<String, Analyzer> fieldAnalyzers = new HashMap<>();
        fieldAnalyzers.put(FIELD_CONTENT, new SmartChineseAnalyzer());
        fieldAnalyzers.put(FIELD_TITLE, new SmartChineseAnalyzer());
        return new PerFieldAnalyzerWrapper(new StandardAnalyzer(), fieldAnalyzers);
    }

    /**
     * 创建基于 jieba 的 Lucene 分析器
     */
    private Analyzer createJiebaAnalyzer() {
        return new Analyzer() {
            private JiebaSegmenter segmenter;

            private JiebaSegmenter getSegmenter() {
                if (segmenter == null) {
                    synchronized (this) {
                        if (segmenter == null) {
                            segmenter = new JiebaSegmenter();
                        }
                    }
                }
                return segmenter;
            }

            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new Tokenizer() {
                    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
                    private final PositionIncrementAttribute posIncrAtt = addAttribute(PositionIncrementAttribute.class);
                    private Iterator<SegToken> tokens;
                    private int skippedPositions = 0;

                    @Override
                    public boolean incrementToken() throws IOException {
                        if (tokens == null) {
                            // 读取全部输入
                            StringBuilder sb = new StringBuilder();
                            char[] buf = new char[1024];
                            int len;
                            while ((len = input.read(buf, 0, buf.length)) != -1) {
                                sb.append(buf, 0, len);
                            }
                            List<SegToken> segTokens = getSegmenter()
                                    .process(sb.toString(), JiebaSegmenter.SegMode.INDEX);
                            tokens = segTokens.iterator();
                            skippedPositions = 0;
                        }
                        while (tokens.hasNext()) {
                            SegToken token = tokens.next();
                            String word = token.word.trim().toLowerCase();
                            if (word.length() >= 2) {
                                termAtt.setEmpty().append(word);
                                posIncrAtt.setPositionIncrement(1 + skippedPositions);
                                skippedPositions = 0;
                                return true;
                            }
                            // 被过滤掉的短词需要累积位置增量
                            skippedPositions++;
                        }
                        return false;
                    }

                    @Override
                    public void reset() throws IOException {
                        super.reset();
                        tokens = null;
                        skippedPositions = 0;
                    }
                };
                return new TokenStreamComponents(tokenizer, new LowerCaseFilter(tokenizer));
            }
        };
    }

    // ==================== 索引管理 ====================

    /**
     * 添加文档到索引
     */
    public void addDocument(String docId, String content, Map<String, String> metadata) {
        try {
            Document doc = buildLuceneDocument(docId, content, metadata);

            // 先删除同 docId 的旧文档（upsert 语义）
            indexWriter.updateDocument(new Term(FIELD_DOC_ID, docId), doc);
            indexWriter.commit(); // 立即持久化到磁盘，防止重启丢失

            // 更新内存缓存
            contentCache.put(docId, content);
            metadataCache.put(docId, metadata != null ? new HashMap<>(metadata) : new HashMap<>());
            refreshReader();

            log.debug("文档 {} 已添加到 Lucene 索引", docId);
        } catch (IOException e) {
            log.error("添加文档到索引失败: {}: {}", docId, e.getMessage());
        }
    }

    /**
     * 批量添加文档（upsert 语义，逐个更新避免批次失败导致全部丢失）
     */
    public void addDocuments(List<DocRecord> records) {
        int successCount = 0;
        for (DocRecord record : records) {
            try {
                Document doc = buildLuceneDocument(record.docId(), record.content(), record.metadata());
                // 使用 updateDocument 实现 upsert：同 docId 的旧文档会被自动替换
                indexWriter.updateDocument(new Term(FIELD_DOC_ID, record.docId()), doc);
                contentCache.put(record.docId(), record.content());
                metadataCache.put(record.docId(),
                        record.metadata() != null ? new HashMap<>(record.metadata()) : new HashMap<>());
                successCount++;
            } catch (IOException e) {
                log.error("文档 {} 索引失败（跳过继续）: {}", record.docId(), e.getMessage());
            }
        }
        try {
            indexWriter.commit(); // 立即持久化，防止重启丢失
            refreshReader();
        } catch (IOException e) {
            log.error("提交 Lucene 索引失败: {}", e.getMessage());
        }
        log.info("批量添加文档到 Lucene 索引完成: {}/{} 成功", successCount, records.size());
    }

    /**
     * 删除文档
     */
    public void removeDocument(String docId) {
        try {
            indexWriter.deleteDocuments(new Term(FIELD_DOC_ID, docId));
            indexWriter.commit(); // 立即持久化
            contentCache.remove(docId);
            metadataCache.remove(docId);
            refreshReader();
            log.debug("文档 {} 已从 Lucene 索引移除", docId);
        } catch (IOException e) {
            log.error("删除文档失败: {}: {}", docId, e.getMessage());
        }
    }

    /**
     * 按文件名删除所有相关文档
     */
    public void removeByFileName(String fileName) {
        try {
            // 先找出需要删除的 docId
            List<String> toRemove = metadataCache.entrySet().stream()
                    .filter(e -> fileName.equals(e.getValue().get("file_name")))
                    .map(Map.Entry::getKey)
                    .toList();

            if (!toRemove.isEmpty()) {
                indexWriter.deleteDocuments(new Term(FIELD_FILE_NAME, fileName));
                indexWriter.commit(); // 立即持久化
                toRemove.forEach(docId -> {
                    contentCache.remove(docId);
                    metadataCache.remove(docId);
                });
                refreshReader();
            }
            log.info("已移除文件 {} 的 {} 个索引条目", fileName, toRemove.size());
        } catch (IOException e) {
            log.error("按文件名删除文档失败: {}: {}", fileName, e.getMessage());
        }
    }

    /**
     * 清空所有索引
     */
    public void clearAll() {
        try {
            indexWriter.deleteAll();
            indexWriter.commit();
            contentCache.clear();
            metadataCache.clear();
            refreshReader();
            log.info("Lucene 索引已全部清空");
        } catch (IOException e) {
            log.error("清空索引失败: {}", e.getMessage());
        }
    }

    // ==================== 检索 ====================

    /**
     * BM25 全文检索
     */
    public List<SearchResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        try {
            refreshReader();
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            searcher.setSimilarity(new BM25Similarity(BM25_K1, BM25_B));

            // 使用 QueryParser 解析查询
            org.apache.lucene.queryparser.classic.QueryParser parser =
                    new org.apache.lucene.queryparser.classic.QueryParser(FIELD_CONTENT, analyzer);
            parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
            // 容错：转义特殊字符
            String escapedQuery = org.apache.lucene.queryparser.classic.QueryParser.escape(query);

            Query luceneQuery = parser.parse(escapedQuery);
            TopDocs topDocs = searcher.search(luceneQuery, maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String docId = doc.get(FIELD_DOC_ID);
                String content = contentCache.getOrDefault(docId, doc.get(FIELD_CONTENT));
                Map<String, String> metadata = metadataCache.getOrDefault(docId, extractStoredFields(doc));

                results.add(new SearchResult(docId, content, metadata, scoreDoc.score));
            }

            return results;
        } catch (Exception e) {
            log.warn("Lucene 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * BM25 全文检索（自定义 k1/b 参数，用于调参对比）
     */
    public List<SearchResult> searchWithParams(String query, int maxResults, float k1, float b) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        try {
            refreshReader();
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            searcher.setSimilarity(new BM25Similarity(k1, b));

            org.apache.lucene.queryparser.classic.QueryParser parser =
                    new org.apache.lucene.queryparser.classic.QueryParser(FIELD_CONTENT, analyzer);
            parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
            String escapedQuery = org.apache.lucene.queryparser.classic.QueryParser.escape(query);
            Query luceneQuery = parser.parse(escapedQuery);
            TopDocs topDocs = searcher.search(luceneQuery, maxResults);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String docId = doc.get(FIELD_DOC_ID);
                String content = contentCache.getOrDefault(docId, doc.get(FIELD_CONTENT));
                Map<String, String> metadata = metadataCache.getOrDefault(docId, extractStoredFields(doc));
                results.add(new SearchResult(docId, content, metadata, scoreDoc.score));
            }
            return results;
        } catch (Exception e) {
            log.warn("Lucene 自定义参数检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * BM25 得分解释：按词拆解每个查询词对最终分数的贡献
     *
     * BM25 公式：score = Σ IDF(qi) × (tf(qi) × (k1+1)) / (tf(qi) + k1 × (1 - b + b × |D|/avgdl))
     */
    public List<BM25Explanation> explainBM25(String query, int maxResults) {
        if (query == null || query.isBlank()) return Collections.emptyList();
        try {
            refreshReader();
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            searcher.setSimilarity(new BM25Similarity(BM25_K1, BM25_B));

            org.apache.lucene.queryparser.classic.QueryParser parser =
                    new org.apache.lucene.queryparser.classic.QueryParser(FIELD_CONTENT, analyzer);
            parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
            String escapedQuery = org.apache.lucene.queryparser.classic.QueryParser.escape(query);
            Query luceneQuery = parser.parse(escapedQuery);
            TopDocs topDocs = searcher.search(luceneQuery, maxResults);

            int totalDocs = directoryReader.numDocs();
            float avgDocLen = getAvgDocLength(searcher);

            // 提取查询词
            Set<org.apache.lucene.index.Term> queryTerms = new HashSet<>();
            luceneQuery.visit(new QueryVisitor() {
                @Override
                public void consumeTerms(Query source, org.apache.lucene.index.Term... terms) {
                    for (org.apache.lucene.index.Term t : terms) queryTerms.add(t);
                }
                @Override public boolean acceptField(String field) { return FIELD_CONTENT.equals(field); }
            });

            List<BM25Explanation> explanations = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String docId = doc.get(FIELD_DOC_ID);

                // 文档长度
                int docLen = getDocLength(searcher, scoreDoc.doc);

                List<TermContribution> contributions = new ArrayList<>();
                for (org.apache.lucene.index.Term term : queryTerms) {
                    // 使用 IndexReader.docFreq() 获取真实的文档频率
                    int df = directoryReader.docFreq(term);
                    double idf = Math.log((totalDocs - df + 0.5) / (df + 0.5) + 1.0);

                    int tf = getTermFreq(searcher, scoreDoc.doc, term.text());
                    double tfNorm = (tf * (BM25_K1 + 1))
                            / (tf + BM25_K1 * (1 - BM25_B + BM25_B * docLen / avgDocLen));
                    double contribution = idf * tfNorm;

                    contributions.add(new TermContribution(
                            term.text(), df, idf, tf, tfNorm, contribution));
                }

                // 按贡献度降序排列
                contributions.sort(Comparator.comparingDouble(TermContribution::contribution).reversed());

                // 计算 BM25 手动计算的得分（贡献值之和）
                double bm25CalculatedScore = contributions.stream()
                        .mapToDouble(TermContribution::contribution)
                        .sum();

                String fileName = doc.get(FIELD_FILE_NAME);
                explanations.add(new BM25Explanation(
                        fileName != null ? fileName : docId,
                        docId,
                        scoreDoc.score,
                        scoreDoc.score,
                        bm25CalculatedScore,
                        docLen,
                        avgDocLen,
                        BM25_K1,
                        BM25_B,
                        totalDocs,
                        contributions));
            }
            return explanations;
        } catch (Exception e) {
            log.warn("BM25 解释失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== BM25 解释辅助方法 ====================

    private int getTermFreq(IndexSearcher searcher, int doc, String termText) {
        try {
            PostingsEnum postings = MultiTerms.getTermPostingsEnum(
                    searcher.getIndexReader(), FIELD_CONTENT,
                    new BytesRef(termText));
            if (postings == null) return 0;
            if (postings.advance(doc) == doc) {
                return postings.freq();
            }
            return 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private int getDocLength(IndexSearcher searcher, int doc) {
        try {
            IndexReader reader = searcher.getIndexReader();
            List<LeafReaderContext> leaves = reader.leaves();
            for (LeafReaderContext ctx : leaves) {
                int docBase = ctx.docBase;
                int maxDoc = ctx.reader().maxDoc();
                if (doc >= docBase && doc < docBase + maxDoc) {
                    int localDoc = doc - docBase;
                    Terms terms = ctx.reader().terms(FIELD_CONTENT);
                    if (terms == null) return 0;
                    TermsEnum termsEnum = terms.iterator();
                    PostingsEnum postings = null;
                    long totalFreq = 0;
                    while (termsEnum.next() != null) {
                        postings = termsEnum.postings(postings, PostingsEnum.FREQS);
                        if (postings.advance(localDoc) == localDoc) {
                            totalFreq += postings.freq();
                        }
                    }
                    return (int) totalFreq;
                }
            }
            return 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private float getAvgDocLength(IndexSearcher searcher) {
        try {
            CollectionStatistics stats = searcher.collectionStatistics(FIELD_CONTENT);
            return stats.sumTotalTermFreq() > 0
                    ? (float) stats.sumTotalTermFreq() / stats.docCount() : 1f;
        } catch (Exception e) {
            return 1f;
        }
    }

    /**
     * 精确 metadata 过滤检索
     */
    public List<SearchResult> searchByMetadata(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            refreshReader();
            IndexSearcher searcher = new IndexSearcher(directoryReader);

            // 构建 BooleanQuery: 所有 filter 条件用 MUST + FILTER
            BooleanQuery.Builder builder = new BooleanQuery.Builder();
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                String field = mapMetadataField(filter.getKey());
                builder.add(new TermQuery(new Term(field, filter.getValue())),
                        BooleanClause.Occur.FILTER);
            }
            // 加一个 MatchAllDocs 确保有评分基础
            builder.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);

            TopDocs topDocs = searcher.search(builder.build(), Integer.MAX_VALUE);

            List<SearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String docId = doc.get(FIELD_DOC_ID);
                String content = contentCache.getOrDefault(docId, doc.get(FIELD_CONTENT));
                Map<String, String> metadata = metadataCache.getOrDefault(docId, extractStoredFields(doc));
                results.add(new SearchResult(docId, content, metadata, 1.0));
            }
            return results;
        } catch (IOException e) {
            log.warn("Metadata 过滤检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 构建 Lucene Document
     */
    private Document buildLuceneDocument(String docId, String content, Map<String, String> metadata) {
        Document doc = new Document();

        // docId：精确索引 + 存储
        doc.add(new StringField(FIELD_DOC_ID, docId, Field.Store.YES));

        // content：全文索引 + 存储（BM25 评分的主要字段）
        doc.add(new TextField(FIELD_CONTENT, content != null ? content : "", Field.Store.YES));

        // metadata 字段：精确索引 + 存储（用于过滤和结果返回）
        if (metadata != null) {
            addMetadataField(doc, FIELD_FILE_NAME, metadata.get("file_name"));
            addMetadataField(doc, FIELD_TITLE, metadata.get("title"));
            addMetadataField(doc, FIELD_API_PATH, metadata.get("api_path"));
            addMetadataField(doc, FIELD_HTTP_METHOD, metadata.get("http_method"));
            addMetadataField(doc, FIELD_TAGS, metadata.get("tags"));
            addMetadataField(doc, FIELD_SOURCE_TYPE, metadata.get("source_type"));

            // 存储所有原始 metadata 字段（用于结果返回）
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String field = entry.getKey();
                // 跳过已添加的标准字段
                if (!Set.of("file_name", "title", "api_path", "http_method", "tags", "source_type")
                        .contains(field) && entry.getValue() != null) {
                    doc.add(new StringField("meta_" + field, entry.getValue(), Field.Store.YES));
                }
            }
        }

        return doc;
    }

    private void addMetadataField(Document doc, String fieldName, String value) {
        if (value != null && !value.isEmpty()) {
            doc.add(new StringField(fieldName, value, Field.Store.YES));
        }
    }

    /**
     * 将用户 metadata key 映射到 Lucene 字段名
     */
    private String mapMetadataField(String key) {
        return switch (key) {
            case "file_name" -> FIELD_FILE_NAME;
            case "title" -> FIELD_TITLE;
            case "api_path" -> FIELD_API_PATH;
            case "http_method" -> FIELD_HTTP_METHOD;
            case "tags" -> FIELD_TAGS;
            case "source_type" -> FIELD_SOURCE_TYPE;
            default -> "meta_" + key;
        };
    }

    /**
     * 从 Lucene Document 提取 stored fields 为 Map
     */
    private Map<String, String> extractStoredFields(Document doc) {
        Map<String, String> result = new HashMap<>();
        for (IndexableField field : doc.getFields()) {
            String name = field.name();
            String value = field.stringValue();
            if (value != null && !name.equals(FIELD_DOC_ID) && !name.equals(FIELD_CONTENT)) {
                // 还原 meta_ 前缀
                if (name.startsWith("meta_")) {
                    result.put(name.substring(5), value);
                } else {
                    result.put(name, value);
                }
            }
        }
        return result;
    }

    /**
     * 刷新 DirectoryReader（近实时搜索）
     */
    private void refreshReader() {
        try {
            DirectoryReader newReader = DirectoryReader.openIfChanged(directoryReader, indexWriter);
            if (newReader != null) {
                DirectoryReader oldReader = directoryReader;
                directoryReader = newReader;
                oldReader.close();
            }
        } catch (IOException e) {
            log.warn("刷新 Lucene reader 失败: {}", e.getMessage());
        }
    }

    // ==================== 统计 ====================

    public IndexStats getStats() {
        return new IndexStats(
                contentCache.size(),
                directoryReader.numDocs(),
                metadataCache.size()
        );
    }

    // ==================== 启动恢复 ====================

    /**
     * 从持久化 Lucene 索引恢复内存缓存
     * 重启时自动调用，无需重新解析文档即可恢复 BM25 检索能力
     */
    private void loadCacheFromIndex() {
        try {
            int count = 0;
            IndexSearcher searcher = new IndexSearcher(directoryReader);
            // 用 MatchAllDocsQuery 扫描全部文档
            TopDocs topDocs = searcher.search(new MatchAllDocsQuery(), Integer.MAX_VALUE);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(scoreDoc.doc);
                String docId = doc.get(FIELD_DOC_ID);
                if (docId == null) continue;

                String content = doc.get(FIELD_CONTENT);
                if (content != null) {
                    contentCache.put(docId, content);
                }

                Map<String, String> metadata = extractStoredFields(doc);
                metadataCache.put(docId, metadata);
                count++;
            }
            if (count > 0) {
                log.info("从持久化索引恢复了 {} 个文档的缓存", count);
            }
        } catch (IOException e) {
            log.warn("从 Lucene 索引恢复缓存失败，将在首次加载时重建: {}", e.getMessage());
        }
    }

    // ==================== 生命周期 ====================

    @PreDestroy
    public void shutdown() {
        try {
            indexWriter.commit();
            directoryReader.close();
            indexWriter.close();
            directory.close();
            log.info("Lucene 索引服务已关闭，数据已持久化到 {}", indexPath);
        } catch (IOException e) {
            log.warn("关闭 Lucene 索引服务失败: {}", e.getMessage());
        }
    }

    // ========== 数据类 ==========

    public record DocRecord(String docId, String content, Map<String, String> metadata) {}

    public record SearchResult(String docId, String content, Map<String, String> metadata, double score) {}

    public record IndexStats(int docCount, int termCount, int metadataCount) {}

    /** BM25 得分解释：单条文档的各词贡献明细 */
    public record BM25Explanation(
            String docId, String rawDocId, double totalScore,
            double luceneScore, double bm25CalculatedScore,
            int docLength, float avgDocLength, float k1, float b, int totalDocs,
            List<TermContribution> terms) {}

    /** 单个查询词对 BM25 分数的贡献 */
    public record TermContribution(
            String term, int docFreq, double idf, int tf, double tfNorm, double contribution) {}
}
