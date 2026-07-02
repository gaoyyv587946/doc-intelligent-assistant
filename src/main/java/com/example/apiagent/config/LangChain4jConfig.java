package com.example.apiagent.config;

import com.example.apiagent.parser.ApiDocumentSplitter;
import com.example.apiagent.store.EmbeddingStorePersistence;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Constructor;

/**
 * LangChain4j核心配置
 *
 * ChatModel: DeepSeek V4 / MiMo / GPT等OpenAI兼容API（云端）
 * EmbeddingModel: 支持切换，默认AllMiniLmL6V2，推荐升级为bge-small-zh
 *
 * 切换中文Embedding模型步骤：
 * 1. pom.xml 取消 bge-small-zh 依赖的注释
 * 2. application.yml 设置 embedding.model-type=bge-small-zh
 * 3. 删除旧的 embedding-store-data.json
 * 4. 重新上传所有文档
 */
@Configuration
@Data
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    /**
     * BGE中文Embedding模型的类名（通过反射加载，避免硬依赖）
     * 当 pom.xml 中取消注释 langchain4j-embeddings-bge-small-zh-v15-q 后，
     * 设置 embedding.model-type=bge-small-zh 即可启用
     */
    private static final String BGE_SMALL_ZH_CLASS =
            "dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel";

    private String testStr = "fuck";

    // ==================== ChatModel ====================
    // 支持DeepSeek V4、MiMo、GPT等OpenAI兼容API
    @Bean
    public OpenAiChatModel chatModel(
            @Value("${chat.model.api-key}") String apiKey,
            @Value("${chat.model.base-url}") String baseUrl,
            @Value("${chat.model.model-name}") String modelName,
            @Value("${chat.model.temperature:0.3}") double temperature,
            @Value("${chat.model.max-tokens:2000}") int maxTokens) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    // ==================== StreamingChatModel ====================
    // 流式输出版本，用于SSE实时返回LLM响应
    @Bean
    public OpenAiStreamingChatModel streamingChatModel(
            @Value("${chat.model.api-key}") String apiKey,
            @Value("${chat.model.base-url}") String baseUrl,
            @Value("${chat.model.model-name}") String modelName,
            @Value("${chat.model.temperature:0.3}") double temperature,
            @Value("${chat.model.max-tokens:2000}") int maxTokens) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .build();
    }

    // ==================== EmbeddingModel ====================
    // 支持通过配置切换模型类型
    // embedding.model-type=all-minilm（默认）: AllMiniLmL6V2，英文为主，384维
    // embedding.model-type=bge-small-zh: BGE中文模型，512维，中文专精
    @Bean
    public EmbeddingModel embeddingModel(
            @Value("${embedding.model-type:all-minilm}") String modelType) {

        if ("bge-small-zh".equalsIgnoreCase(modelType)) {
            try {
                Class<?> clazz = Class.forName(BGE_SMALL_ZH_CLASS);
                Constructor<?> constructor = clazz.getDeclaredConstructor();
                EmbeddingModel model = (EmbeddingModel) constructor.newInstance();
                log.info("已加载BGE中文Embedding模型 (bge-small-zh-v1.5, 512维)");
                return model;
            } catch (ClassNotFoundException e) {
                log.warn("BGE中文模型依赖未添加（请在pom.xml中取消注释 langchain4j-embeddings-bge-small-zh-v15-q），" +
                        "回退到AllMiniLmL6V2");
            } catch (Exception e) {
                log.warn("BGE中文模型加载失败: {}，回退到AllMiniLmL6V2", e.getMessage());
            }
        }

        log.info("使用AllMiniLmL6V2 Embedding模型 (384维)");
        return new AllMiniLmL6V2EmbeddingModel();
    }

    // ==================== EmbeddingStore ====================
    // 优先从持久化文件恢复，避免重复计算Embedding
    @Bean
    public InMemoryEmbeddingStore<TextSegment> embeddingStore(
            @Value("${embedding.persistence-path:embedding-store-data.json}") String persistencePath) {
        return EmbeddingStorePersistence.loadFromFile(persistencePath);
    }

    // ==================== EmbeddingStoreIngestor ====================
    // 负责：文档分片（结构化分片） → Embedding向量化 → 存入Store
    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor(
            EmbeddingModel embeddingModel,
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.chunk-size:1500}") int chunkSize,
            @Value("${rag.chunk-min-size:100}") int chunkMinSize) {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(new ApiDocumentSplitter(chunkSize, chunkMinSize))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }
}
