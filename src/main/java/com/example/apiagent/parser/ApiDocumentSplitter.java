package com.example.apiagent.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档结构化分片器
 *
 * 策略：
 * 1. 优先按 Markdown 二级标题（## ）切分，每个章节作为一个 chunk
 * 2. 如果单个 section 超过 maxSize 字符，按段落（\n\n）二次切分，每段保留标题前缀
 * 3. 如果文档无二级标题，退化为按段落切分，再按 maxSize 合并
 * 4. 文档头部（第一个 ## 之前的内容）作为公共前缀拼接到每个 chunk
 */
public class ApiDocumentSplitter implements DocumentSplitter {

    private static final Logger log = LoggerFactory.getLogger(ApiDocumentSplitter.class);

    private static final Pattern SECTION_PATTERN = Pattern.compile("(?=^## )", Pattern.MULTILINE);
    private static final Pattern H1_PATTERN = Pattern.compile("^# .+$", Pattern.MULTILINE);
    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("(?<=[。！？.!?])");

    private final int maxSize;
    private final int minSize;

    /**
     * @param maxSize 单个 chunk 的最大字符数
     * @param minSize 单个 chunk 的最小字符数（过短的 section 会和前一个合并）
     */
    public ApiDocumentSplitter(int maxSize, int minSize) {
        this.maxSize = maxSize;
        this.minSize = minSize;
    }

    public ApiDocumentSplitter(int maxSize) {
        this(maxSize, 100);
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        Metadata baseMetadata = document.metadata() != null ? document.metadata() : new Metadata();

        // 提取文档头部（第一个 ## 之前的内容）
        String header = extractHeader(text);
        String body = text.substring(header.length()).trim();

        if (body.isEmpty()) {
            // 没有 section，按段落切分
            return splitByParagraphs(text, baseMetadata);
        }

        // 按 ## 切分为 sections
        String[] sections = SECTION_PATTERN.split(body);

        List<TextSegment> segments = new ArrayList<>();
        for (String section : sections) {
            if (section.isBlank()) continue;

            String sectionTitle = extractSectionTitle(section);
            String sectionContent = section.trim();

            if (sectionContent.length() <= maxSize) {
                // 整个 section 作为一个 chunk
                String chunkText = buildChunkText(header, sectionContent);
                if (chunkText.length() > maxSize) {
                    // 加上 header 后超长，需要切分
                    splitLargeSection(chunkText, sectionTitle, header, baseMetadata, segments);
                } else {
                    segments.add(TextSegment.from(chunkText, copyMetadata(baseMetadata)));
                }
            } else {
                // section 过长，按段落二次切分
                splitLargeSection(sectionContent, sectionTitle, header, baseMetadata, segments);
            }
        }

        // 合并过短的 chunk
        segments = mergeShortSegments(segments);

        log.debug("文档分片完成，共 {} 个片段", segments.size());
        return segments;
    }

    @Override
    public List<TextSegment> splitAll(List<Document> documents) {
        List<TextSegment> allSegments = new ArrayList<>();
        for (Document document : documents) {
            allSegments.addAll(split(document));
        }
        return allSegments;
    }

    /**
     * 提取文档头部：第一个 ## 之前的所有内容
     */
    private String extractHeader(String text) {
        int firstSection = text.indexOf("\n## ");
        if (firstSection == -1) {
            // 也检查文档开头的 ##
            if (text.startsWith("## ")) {
                return "";
            }
            return text;
        }
        return text.substring(0, firstSection).trim();
    }

    /**
     * 提取 section 的标题行
     */
    private String extractSectionTitle(String section) {
        int newlineIdx = section.indexOf('\n');
        if (newlineIdx == -1) return section.trim();
        String firstLine = section.substring(0, newlineIdx).trim();
        if (firstLine.startsWith("## ")) {
            return firstLine;
        }
        return "";
    }

    /**
     * 构建 chunk 文本，如果有 header 则拼接
     */
    private String buildChunkText(String header, String content) {
        if (header.isEmpty()) {
            return content;
        }
        return header + "\n\n" + content;
    }

    /**
     * 切分过长的 section
     */
    private void splitLargeSection(String content, String title, String header,
                                   Metadata baseMetadata, List<TextSegment> segments) {
        String[] paragraphs = content.split("\n\n", -1);
        StringBuilder currentChunk = new StringBuilder();

        // 如果不是以 header 开头，加上 header + title 作为前缀
        String prefix = header.isEmpty() ? "" : header + "\n\n";

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) continue;

            if (currentChunk.length() + paragraph.length() + 2 > maxSize && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                currentChunk = new StringBuilder();
                // 新 chunk 保留标题前缀
                if (!title.isEmpty()) {
                    currentChunk.append(prefix).append(title).append("\n\n");
                } else if (!prefix.isEmpty()) {
                    currentChunk.append(prefix);
                }
            }

            if (paragraph.length() > maxSize) {
                // 单个段落超长，按句子切分
                if (currentChunk.length() > minSize) {
                    segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                    currentChunk = new StringBuilder();
                    if (!title.isEmpty()) {
                        currentChunk.append(prefix).append(title).append("\n\n");
                    }
                }
                splitLongParagraph(paragraph, title, prefix, baseMetadata, segments, currentChunk);
            } else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        if (currentChunk.length() > 0) {
            segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
        }
    }

    /**
     * 切分超长段落（按句子）
     */
    private void splitLongParagraph(String paragraph, String title, String prefix,
                                    Metadata baseMetadata, List<TextSegment> segments,
                                    StringBuilder currentChunk) {
        String[] sentences = SENTENCE_END_PATTERN.split(paragraph);

        for (String sentence : sentences) {
            if (sentence.isBlank()) continue;

            if (currentChunk.length() + sentence.length() > maxSize && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                currentChunk.setLength(0);
                if (!title.isEmpty()) {
                    currentChunk.append(prefix).append(title).append("\n\n");
                }
            }
            currentChunk.append(sentence);
        }
    }

    /**
     * 无 ## 标题时按段落切分
     */
    private List<TextSegment> splitByParagraphs(String text, Metadata baseMetadata) {
        List<TextSegment> segments = new ArrayList<>();
        String[] paragraphs = text.split("\n\n", -1);
        StringBuilder currentChunk = new StringBuilder();

        for (String paragraph : paragraphs) {
            if (paragraph.isBlank()) continue;

            if (currentChunk.length() + paragraph.length() + 2 > maxSize && currentChunk.length() > 0) {
                segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                currentChunk = new StringBuilder();
            }

            if (paragraph.length() > maxSize) {
                if (currentChunk.length() > 0) {
                    segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                    currentChunk = new StringBuilder();
                }
                // 按句子切分超长段落
                String[] sentences = SENTENCE_END_PATTERN.split(paragraph);
                for (String sentence : sentences) {
                    if (sentence.isBlank()) continue;
                    if (currentChunk.length() + sentence.length() > maxSize && currentChunk.length() > 0) {
                        segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
                        currentChunk = new StringBuilder();
                    }
                    currentChunk.append(sentence);
                }
            } else {
                if (currentChunk.length() > 0) currentChunk.append("\n\n");
                currentChunk.append(paragraph);
            }
        }

        if (currentChunk.length() > 0) {
            segments.add(TextSegment.from(currentChunk.toString().trim(), copyMetadata(baseMetadata)));
        }

        return mergeShortSegments(segments);
    }

    /**
     * 合并过短的相邻 chunk
     */
    private List<TextSegment> mergeShortSegments(List<TextSegment> segments) {
        if (segments.size() <= 1) return segments;

        List<TextSegment> merged = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        Metadata lastMetadata = null;

        for (TextSegment segment : segments) {
            if (buffer.length() == 0) {
                buffer.append(segment.text());
                lastMetadata = segment.metadata();
            } else if (buffer.length() + segment.text().length() + 2 <= maxSize && segment.text().length() < minSize) {
                buffer.append("\n\n").append(segment.text());
            } else {
                merged.add(TextSegment.from(buffer.toString(), lastMetadata));
                buffer = new StringBuilder(segment.text());
                lastMetadata = segment.metadata();
            }
        }

        if (buffer.length() > 0) {
            merged.add(TextSegment.from(buffer.toString(), lastMetadata));
        }

        return merged;
    }

    private Metadata copyMetadata(Metadata source) {
        return Metadata.from(source.toMap());
    }
}
