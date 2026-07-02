package com.example.apiagent.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本解析器（兜底解析器）
 * 支持.txt文件，直接读取文本内容
 */
@Component
public class TextParser implements DocumentParser {

    @Override
    public List<Document> parse(InputStream inputStream, Metadata metadata) {
        try {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            Document doc = Document.from(content, metadata);
            return List.of(doc);
        } catch (Exception e) {
            throw new RuntimeException("解析文本文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".txt");
    }
}
