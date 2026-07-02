package com.example.apiagent.service;

import com.example.apiagent.filter.SensitiveDataFilter;
import com.example.apiagent.parser.DocumentParser;
import com.example.apiagent.parser.ParserFactory;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档服务
 *
 * 完整流程：上传 -> 解析原文 -> LLM格式化 -> 保存本地docs -> 增量入向量库和关键词索引
 *
 * 功能：
 * 1. 启动时从本地docs目录自动加载文档
 * 2. 上传文档后通过LLM格式化为标准格式
 * 3. 格式化后的文档保存到本地docs目录（持久化）
 * 4. 同名文档增量更新（先删旧向量和关键词索引，再加新的）
 * 5. 文档入库前自动脱敏敏感信息
 * 6. 支持混合检索（向量 + BM25关键词）
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final ParserFactory parserFactory;
    private final EmbeddingStoreIngestor ingestor;
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final OpenAiChatModel chatModel;
    private final KeywordIndexService keywordIndexService;
    private final String localDocsPath;
    private final int formatMaxChunkSize;
    private final boolean metadataLlmEnhance;

    // API路径模式：`GET /api/xxx`
    private static final Pattern API_PATH_PATTERN = Pattern.compile(
            "`(GET|POST|PUT|DELETE|PATCH)\\s+(/[^`]+)`"
    );

    // 标题模式：## 标题
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "^##\\s+(.+)$", Pattern.MULTILINE
    );

    // 标签模式：标签：xxx
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "标签[：:]\\s*(.+)$", Pattern.MULTILINE
    );

    // 参数名模式：从 Markdown 表格中提取参数名（第一列）
    private static final Pattern PARAM_TABLE_PATTERN = Pattern.compile(
            "^\\|\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\|", Pattern.MULTILINE
    );

    // 参数名模式：从描述文本中提取参数名（如 `paramName` 或 param_name:）
    private static final Pattern PARAM_INLINE_PATTERN = Pattern.compile(
            "`([a-zA-Z_][a-zA-Z0-9_]{1,30})`"
    );

    // 返回JSON字段名：提取 "fieldName": 或 "fieldName" : 模式
    private static final Pattern RESPONSE_FIELD_PATTERN = Pattern.compile(
            "\"([a-zA-Z_][a-zA-Z0-9_]*)\"\\s*:"
    );

    // 接口描述模式：紧跟在标题或路径后的第一行非空文本
    private static final Pattern DESC_PATTERN = Pattern.compile(
            "(?:^##\\s+.+$|^`(?:GET|POST|PUT|DELETE|PATCH)\\s+/[^`]+`\\s*$)\\s*\\n+(.+)$",
            Pattern.MULTILINE
    );

    // 一级标题模式（用于分类）
    private static final Pattern H1_PATTERN = Pattern.compile(
            "^#\\s+(.+)$", Pattern.MULTILINE
    );

    public DocumentService(
            ParserFactory parserFactory,
            EmbeddingStoreIngestor ingestor,
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            SensitiveDataFilter sensitiveDataFilter,
            OpenAiChatModel chatModel,
            KeywordIndexService keywordIndexService,
            @Value("${rag.local-docs-path:docs}") String localDocsPath,
            @Value("${rag.format-max-chunk-size:2000}") int formatMaxChunkSize,
            @Value("${rag.metadata.llm-enhance:false}") boolean metadataLlmEnhance) {
        this.parserFactory = parserFactory;
        this.ingestor = ingestor;
        this.embeddingStore = embeddingStore;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.chatModel = chatModel;
        this.keywordIndexService = keywordIndexService;
        this.localDocsPath = localDocsPath;
        this.formatMaxChunkSize = formatMaxChunkSize;
        this.metadataLlmEnhance = metadataLlmEnhance;
        log.info("DocumentService初始化: metadataLlmEnhance={}", metadataLlmEnhance);
    }

    @PostConstruct
    public void loadDefaultDocuments() {
        try {
            Path docsDir = Path.of(localDocsPath);
            if (!Files.exists(docsDir)) {
                Files.createDirectories(docsDir);
                log.info("本地docs目录不存在，已创建: {}", localDocsPath);
            }

            List<Path> files = Files.list(docsDir)
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> isSupportedFormat(p.getFileName().toString()))
                    .toList();

            if (files.isEmpty()) {
                log.info("本地docs目录为空，跳过自动加载");
                return;
            }

            int totalDocs = 0;
            for (Path file : files) {
                String fileName = file.getFileName().toString();
                try (InputStream is = Files.newInputStream(file)) {
                    totalDocs += loadDocument(is, fileName);
                }
            }

            log.info("本地文档加载完成，共 {} 个文件 {} 个片段", files.size(), totalDocs);
        } catch (Exception e) {
            log.warn("本地文档目录加载失败: {}", e.getMessage());
        }
    }

    public DocumentUploadResult uploadDocument(MultipartFile file, boolean useFormatWithLLM) {
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名为空");
        }

        log.info("开始处理上传文档: {} ({}KB)", fileName, file.getSize() / 1024);

        try {
            DocumentParser parser = parserFactory.getParser(fileName);
            Metadata metadata = new Metadata();
            metadata.put("file_name", fileName);

            List<Document> documents;
            try (InputStream is = file.getInputStream()) {
                documents = parser.parse(is, metadata);
            }

            if (documents.isEmpty()) {
                return new DocumentUploadResult(false, "文档解析结果为空", fileName, 0);
            }

            String formattedContent = documents.stream()
                    .map(Document::text)
                    .reduce("", (a, b) -> a + "\n\n" + b);

            if (useFormatWithLLM) {
                log.info("正在通过LLM格式化文档...");
                formattedContent = formatWithLLM(formattedContent, fileName);
            }

            String outputFileName = generateOutputFileName(fileName);
            saveToLocalDocs(outputFileName, formattedContent);
            log.info("文档已保存到: {}/{}", localDocsPath, outputFileName);

            int fragmentCount = ingestToVectorStore(outputFileName, formattedContent);

            return new DocumentUploadResult(true, "文档处理成功", outputFileName, fragmentCount);

        } catch (Exception e) {
            log.error("文档处理失败: {}", e.getMessage(), e);
            return new DocumentUploadResult(false, "文档处理失败: " + e.getMessage(), fileName, 0);
        }
    }

    private String formatWithLLM(String originalText, String fileName) {
        if (originalText.length() <= formatMaxChunkSize) {
            return formatChunk(originalText, fileName);
        }

        log.info("文档 {} 较大 ({} 字符)，进行分段格式化", fileName, originalText.length());
        List<String> chunks = splitTextIntoChunks(originalText);
        List<String> formattedChunks = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            log.info("格式化第 {}/{} 段...", i + 1, chunks.size());
            String formattedChunk = formatChunk(chunks.get(i), fileName);
            formattedChunks.add(formattedChunk);
        }

        return String.join("\n\n", formattedChunks);
    }

    private List<String> splitTextIntoChunks(String text) {
        List<String> chunks = new ArrayList<>();
        String[] sections = text.split("(?=^## )", -1);

        if (sections.length > 1) {
            StringBuilder currentChunk = new StringBuilder();

            for (String section : sections) {
                if (section.isEmpty()) continue;

                if (currentChunk.length() + section.length() > formatMaxChunkSize && currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }

                if (section.length() > formatMaxChunkSize) {
                    if (currentChunk.length() > 0) {
                        chunks.add(currentChunk.toString().trim());
                        currentChunk = new StringBuilder();
                    }
                    chunks.addAll(splitLargeSection(section));
                } else {
                    currentChunk.append(section).append("\n\n");
                }
            }

            if (currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
            }
        } else {
            chunks = splitByParagraphs(text);
        }

        return chunks;
    }

    private List<String> splitLargeSection(String section) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = section.split("\n\n", -1);
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) continue;

            if (currentChunk.length() + paragraph.length() > formatMaxChunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            if (paragraph.length() > formatMaxChunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                chunks.addAll(splitLargeParagraph(paragraph));
            } else {
                currentChunk.append(paragraph).append("\n\n");
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitByParagraphs(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\n\n", -1);
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) continue;

            if (currentChunk.length() + paragraph.length() > formatMaxChunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            if (paragraph.length() > formatMaxChunkSize) {
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                    currentChunk = new StringBuilder();
                }
                chunks.addAll(splitLargeParagraph(paragraph));
            } else {
                currentChunk.append(paragraph).append("\n\n");
            }
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private List<String> splitLargeParagraph(String paragraph) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = paragraph.split("(?<=[。！？.!?])", -1);
        StringBuilder currentChunk = new StringBuilder();

        for (String sentence : sentences) {
            if (sentence.isEmpty()) continue;

            if (currentChunk.length() + sentence.length() > formatMaxChunkSize && currentChunk.length() > 0) {
                chunks.add(currentChunk.toString().trim());
                currentChunk = new StringBuilder();
            }

            currentChunk.append(sentence);
        }

        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        return chunks;
    }

    private String formatChunk(String chunkText, String fileName) {
        String prompt = """
                请将以下文档内容格式化为标准的文档格式（Markdown）。

                格式要求：
                1. 每个接口单独一个章节，用 ## 标题
                2. 每个接口包含：接口路径、接口描述、请求参数、返回示例、错误码
                3. 参数用表格展示：参数名、类型、是否必填、说明
                4. 返回示例用JSON代码块
                5. 保留原文档中的所有接口信息，不要遗漏
                6. 如果原文档不是接口文档（如功能设计、详细设计），按原文结构保留关键信息，用Markdown格式化

                原始文档内容：
                ---
                %s
                ---

                请直接输出格式化后的Markdown内容，不要添加额外说明。
                """.formatted(chunkText);

        try {
            String response = chatModel.generate(prompt);
            return response;
        } catch (Exception e) {
            log.warn("LLM格式化失败，使用原文: {}", e.getMessage());
            return chunkText;
        }
    }

    private void saveToLocalDocs(String fileName, String content) throws IOException {
        Path docsDir = Path.of(localDocsPath);
        if (!Files.exists(docsDir)) {
            Files.createDirectories(docsDir);
        }

        Path filePath = docsDir.resolve(fileName);
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }

    private int loadDocument(InputStream inputStream, String fileName) {
        DocumentParser parser = parserFactory.getParser(fileName);

        Metadata metadata = new Metadata();
        metadata.put("file_name", fileName);

        List<Document> documents = parser.parse(inputStream, metadata);
        if (documents.isEmpty()) {
            log.warn("文档 {} 解析结果为空", fileName);
            return 0;
        }

        String content = documents.stream()
                .map(Document::text)
                .reduce("", (a, b) -> a + "\n\n" + b);

        return ingestToVectorStore(fileName, content);
    }

    private int ingestToVectorStore(String fileName, String content) {
        try {
            IsEqualTo filter = new IsEqualTo("file_name", fileName);
            embeddingStore.removeAll(filter);
            keywordIndexService.removeByFileName(fileName);
            log.info("已清除文档 {} 的旧向量和关键词索引", fileName);
        } catch (Exception e) {
            log.warn("清除旧数据失败（可能是首次加载）: {}", e.getMessage());
        }

        DocumentParser parser = parserFactory.getParser(fileName);
        Metadata metadata = new Metadata();
        metadata.put("file_name", fileName);

        InputStream is = new java.io.ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        List<Document> documents = parser.parse(is, metadata);

        if (documents.isEmpty()) {
            log.warn("文档 {} 格式化后解析为空", fileName);
            return 0;
        }

        List<Document> sanitizedDocs = new ArrayList<>();
        List<KeywordIndexService.DocRecord> keywordRecords = new ArrayList<>();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String text = doc.text();

            if (sensitiveDataFilter.containsSensitiveInfo(text)) {
                text = sensitiveDataFilter.desensitize(text);
                doc = Document.from(text, doc.metadata());
                log.warn("文档 {} 中检测到敏感信息，已脱敏", fileName);
            }
            sanitizedDocs.add(doc);

            String docId = fileName + "#" + i;
            Map<String, String> extractedMetadata = extractMetadata(text, fileName);
            keywordRecords.add(new KeywordIndexService.DocRecord(docId, text, extractedMetadata));
        }

        ingestor.ingest(sanitizedDocs);
        log.info("文档 {} 向量入库完成，{} 个片段", fileName, sanitizedDocs.size());

        keywordIndexService.addDocuments(keywordRecords);
        log.info("文档 {} 关键词索引建立完成，{} 个条目", fileName, keywordRecords.size());

        return sanitizedDocs.size();
    }

    private Map<String, String> extractMetadata(String content, String fileName) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("file_name", fileName);

        // API路径和HTTP方法
        Matcher pathMatcher = API_PATH_PATTERN.matcher(content);
        if (pathMatcher.find()) {
            metadata.put("http_method", pathMatcher.group(1));
            metadata.put("api_path", pathMatcher.group(2));
        }

        // 标题
        Matcher titleMatcher = TITLE_PATTERN.matcher(content);
        if (titleMatcher.find()) {
            metadata.put("title", titleMatcher.group(1).trim());
        }

        // 标签
        Matcher tagMatcher = TAG_PATTERN.matcher(content);
        if (tagMatcher.find()) {
            metadata.put("tags", tagMatcher.group(1).trim());
        }

        // 接口描述摘要（第一行非空文本）
        Matcher descMatcher = DESC_PATTERN.matcher(content);
        if (descMatcher.find()) {
            String desc = descMatcher.group(1).trim();
            if (desc.length() > 200) desc = desc.substring(0, 200);
            if (!desc.startsWith("|") && !desc.startsWith("```")) {
                metadata.put("summary", desc);
            }
        }

        // 参数名列表（从Markdown表格和内联代码提取）
        Set<String> paramNames = new LinkedHashSet<>();
        Matcher paramTableMatcher = PARAM_TABLE_PATTERN.matcher(content);
        while (paramTableMatcher.find()) {
            String param = paramTableMatcher.group(1);
            // 排除常见的表头词
            if (!param.equalsIgnoreCase("name") && !param.equalsIgnoreCase("type")
                    && !param.equalsIgnoreCase("required") && !param.equalsIgnoreCase("description")
                    && !param.equalsIgnoreCase("参数") && !param.equalsIgnoreCase("类型")
                    && !param.equalsIgnoreCase("说明") && !param.equalsIgnoreCase("是否必填")) {
                paramNames.add(param);
            }
        }
        Matcher paramInlineMatcher = PARAM_INLINE_PATTERN.matcher(content);
        while (paramInlineMatcher.find()) {
            String param = paramInlineMatcher.group(1);
            if (param.length() >= 2 && param.length() <= 30) {
                paramNames.add(param);
            }
        }
        if (!paramNames.isEmpty()) {
            metadata.put("param_names", String.join(",", paramNames));
        }

        // 返回字段名（从JSON示例中提取）
        Set<String> responseFields = new LinkedHashSet<>();
        // 找到代码块内的JSON内容
        Pattern codeBlockPattern = Pattern.compile("```(?:json)?\\s*\\n(.*?)```", Pattern.DOTALL);
        Matcher codeBlockMatcher = codeBlockPattern.matcher(content);
        while (codeBlockMatcher.find()) {
            String jsonBlock = codeBlockMatcher.group(1);
            Matcher fieldMatcher = RESPONSE_FIELD_PATTERN.matcher(jsonBlock);
            while (fieldMatcher.find()) {
                responseFields.add(fieldMatcher.group(1));
            }
        }
        if (!responseFields.isEmpty()) {
            // 只取前10个字段，避免元数据过大
            String fields = responseFields.stream().limit(10).collect(Collectors.joining(","));
            metadata.put("response_fields", fields);
        }

        // 分类（从一级标题或文件名推断）
        Matcher h1Matcher = H1_PATTERN.matcher(content);
        if (h1Matcher.find()) {
            metadata.put("category", h1Matcher.group(1).trim());
        } else {
            // 从文件名提取分类
            String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            metadata.put("category", baseName);
        }

        // LLM增强Metadata提取（当正则提取结果不足时）
        if (metadataLlmEnhance && isMetadataSparse(metadata)) {
            Map<String, String> llmMetadata = extractMetadataWithLLM(content);
            // 只补充正则未提取到的字段
            for (Map.Entry<String, String> entry : llmMetadata.entrySet()) {
                metadata.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        return metadata;
    }

    /**
     * 判断正则提取的metadata是否稀疏（缺少关键结构化信息）
     */
    private boolean isMetadataSparse(Map<String, String> metadata) {
        int richFieldCount = 0;
        if (metadata.containsKey("api_path")) richFieldCount++;
        if (metadata.containsKey("title")) richFieldCount++;
        if (metadata.containsKey("param_names")) richFieldCount++;
        if (metadata.containsKey("summary")) richFieldCount++;
        return richFieldCount < 2; // 少于2个丰富字段视为稀疏
    }

    /**
     * LLM智能提取Metadata
     * 当正则提取结果不足时，调用LLM从文档内容中提取结构化信息
     */
    private Map<String, String> extractMetadataWithLLM(String content) {
        Map<String, String> result = new HashMap<>();
        try {
            // 截取前1500字符，避免token超限
            String truncated = content.length() > 1500 ? content.substring(0, 1500) : content;
            String prompt = """
                    从以下文档内容中提取结构化信息。严格按JSON格式输出，不要添加额外说明。
                    只输出你能从文本中确认的信息，不确定的字段不要输出。
                    
                    输出格式（仅输出非空字段）：
                    {
                      "api_path": "接口路径，如/api/users/register",
                      "http_method": "GET或POST或PUT或DELETE或PATCH",
                      "title": "接口中文标题",
                      "summary": "一句话接口描述",
                      "param_names": "参数1,参数2,参数3",
                      "response_fields": "字段1,字段2,字段3",
                      "category": "接口所属模块分类"
                    }
                    
                    文档内容：
                    ---
                    %s
                    ---
                    
                    提取结果：""".formatted(truncated);

            String response = chatModel.generate(prompt);
            // 提取JSON部分
            response = response.strip();
            if (response.startsWith("```")) {
                response = response.replaceAll("```(?:json)?\\s*", "").replaceAll("```$", "").strip();
            }
            if (response.startsWith("{") && response.endsWith("}")) {
                // 简单JSON解析（不引入额外依赖）
                parseJsonMetadata(response, result);
            }
            log.debug("LLM Metadata提取结果: {}", result);
        } catch (Exception e) {
            log.warn("LLM Metadata提取失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 简单JSON解析（仅处理{"key":"value"}格式，不引入Jackson依赖）
     */
    private void parseJsonMetadata(String json, Map<String, String> result) {
        // 移除花括号
        String inner = json.substring(1, json.length() - 1).trim();
        // 按逗号分割（处理value中可能包含逗号的情况）
        Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
        Matcher kvMatcher = kvPattern.matcher(inner);
        while (kvMatcher.find()) {
            String key = kvMatcher.group(1);
            String value = kvMatcher.group(2).trim();
            if (!value.isEmpty()) {
                result.put(key, value);
            }
        }
    }

    private String generateOutputFileName(String originalName) {
        String baseName = originalName.contains(".")
                ? originalName.substring(0, originalName.lastIndexOf('.'))
                : originalName;
        baseName = baseName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
        return baseName + ".md";
    }

    public void clearAllVectors() {
        log.info("开始清除所有向量和关键词索引...");
        embeddingStore.removeAll();
        keywordIndexService.clearAll();
        log.info("所有向量和关键词索引已清除");
    }

    public void clearDocumentVectors(String fileName) {
        log.info("开始清除文档 {} 的向量和关键词索引...", fileName);
        IsEqualTo filter = new IsEqualTo("file_name", fileName);
        embeddingStore.removeAll(filter);
        keywordIndexService.removeByFileName(fileName);
        log.info("文档 {} 的向量和关键词索引已清除", fileName);
    }

    /**
     * 重新加载单个文档
     * 先清除该文档的向量和关键词索引，然后从本地文件重新加载
     *
     * @param fileName 文件名
     * @return 重新加载的片段数
     */
    public int reloadDocument(String fileName) {
        log.info("开始重新加载文档: {}", fileName);
        
        // 检查文件是否存在
        Path filePath = Path.of(localDocsPath, fileName);
        if (!Files.exists(filePath)) {
            throw new RuntimeException("文件不存在: " + filePath);
        }
        
        // 清除该文档的旧向量和关键词索引
        clearDocumentVectors(fileName);
        
        // 重新加载文档
        try (InputStream is = Files.newInputStream(filePath)) {
            int fragmentCount = loadDocument(is, fileName);
            log.info("文档 {} 重新加载完成，共 {} 个片段", fileName, fragmentCount);
            return fragmentCount;
        } catch (IOException e) {
            log.error("重新加载文档失败: {}", e.getMessage(), e);
            throw new RuntimeException("重新加载文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 LLM 生成的文档内容入库（保存到本地 docs + 向量库 + 关键词索引）
     * 供 ProjectDocGeneratorService 调用，复用已有的增量更新和脱敏逻辑
     *
     * @param fileName 生成的文档文件名（如 "项目名_项目文档.md"）
     * @param content  LLM 生成的 Markdown 文档内容
     * @return 入库的文档片段数
     */
    public int ingestGeneratedDoc(String fileName, String content) {
        log.info("开始入库生成的文档: {} ({}字符)", fileName, content.length());
        try {
            saveToLocalDocs(fileName, content);
            log.info("文档已保存到: {}/{}", localDocsPath, fileName);
            int fragmentCount = ingestToVectorStore(fileName, content);
            log.info("生成文档入库完成: {} 个片段", fragmentCount);
            return fragmentCount;
        } catch (Exception e) {
            log.error("生成文档入库失败: {}", e.getMessage(), e);
            throw new RuntimeException("文档入库失败: " + e.getMessage(), e);
        }
    }

    private boolean isSupportedFormat(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".md") || lower.endsWith(".markdown")
                || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".txt")
                || (lower.endsWith(".json") && lower.contains("bookmark"));
    }

    /**
     * 列出 docs 目录下的所有文档文件
     */
    public List<DocumentInfo> listDocuments() {
        try {
            Path docsDir = Path.of(localDocsPath);
            if (!Files.exists(docsDir)) return Collections.emptyList();

            return Files.list(docsDir)
                    .filter(p -> !Files.isDirectory(p))
                    .sorted(Comparator.comparingLong(p -> {
                        try { return -Files.getLastModifiedTime((Path) p).toMillis(); }
                        catch (Exception e) { return 0L; }
                    }))
                    .map(p -> {
                        try {
                            return new DocumentInfo(
                                    p.getFileName().toString(),
                                    Files.size(p),
                                    Files.getLastModifiedTime(p).toString()
                            );
                        } catch (IOException e) {
                            return new DocumentInfo(p.getFileName().toString(), 0, "");
                        }
                    })
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.warn("列出文档失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public record DocumentInfo(String name, long size, String lastModified) {}

    public record DocumentUploadResult(
            boolean success,
            String message,
            String fileName,
            int fragmentCount
    ) {}
}
