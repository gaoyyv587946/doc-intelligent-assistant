package com.example.apiagent.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Word文档解析器
 * 支持.docx格式，使用Apache POI提取段落文本
 */
@Component
public class DocParser implements DocumentParser {

    @Override
    public List<Document> parse(InputStream inputStream, Metadata metadata) {
        try {
            XWPFDocument docx = new XWPFDocument(inputStream);

            String content = docx.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(text -> !text.isBlank())
                    .collect(Collectors.joining("\n"));

            docx.close();

            if (content.isBlank()) {
                return List.of();
            }

            Document doc = Document.from(content, metadata);
            return List.of(doc);
        } catch (Exception e) {
            throw new RuntimeException("解析Word文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".doc") || fileName.endsWith(".docx");
    }
}
