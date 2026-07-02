package com.example.apiagent.store;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 向量库持久化服务
 * 解决InMemoryEmbeddingStore重启后数据丢失的问题
 *
 * 策略：
 * - 启动时：LangChain4jConfig中检查文件是否存在，用fromFile()创建store
 * - 关闭时：自动保存向量数据到JSON文件
 * - 增量更新时embeddingStore自动包含新数据，下次启动自然持久化
 */
@Component
public class EmbeddingStorePersistence {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingStorePersistence.class);

    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final String persistencePath;

    public EmbeddingStorePersistence(
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            @Value("${embedding.persistence-path:embedding-store-data.json}") String persistencePath) {
        this.embeddingStore = embeddingStore;
        this.persistencePath = persistencePath;
    }

    /**
     * 应用关闭时保存向量数据到文件
     */
    @PreDestroy
    public void save() {
        try {
            embeddingStore.serializeToFile(Path.of(persistencePath));
            log.info("向量库数据已保存到 {}", persistencePath);
        } catch (Exception e) {
            log.error("向量库数据保存失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 静态工具方法：从文件加载store（如果文件存在）
     * 在LangChain4jConfig创建Bean时调用
     */
    public static InMemoryEmbeddingStore<TextSegment> loadFromFile(String path) {
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            try {
                InMemoryEmbeddingStore<TextSegment> loaded = InMemoryEmbeddingStore.fromFile(filePath);
                log.info("向量库数据已从 {} 恢复", path);
                return loaded;
            } catch (Exception e) {
                log.warn("向量库数据恢复失败，将创建空store: {}", e.getMessage());
            }
        }
        return new InMemoryEmbeddingStore<>();
    }
}
