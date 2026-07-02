package com.example.apiagent.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Markdown文档解析器
 * 直接将Markdown内容作为整文档交给EmbeddingStoreIngestor的splitter处理
 */
@Component
public class MarkdownParser implements DocumentParser {

    @Override
    public List<Document> parse(InputStream inputStream, Metadata metadata) {
        try {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Document doc = Document.from(content, metadata);
            return List.of(doc);
        } catch (Exception e) {
            throw new RuntimeException("解析Markdown文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".md") || fileName.endsWith(".markdown");
    }
}
