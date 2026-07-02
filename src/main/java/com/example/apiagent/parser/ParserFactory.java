package com.example.apiagent.parser;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文档解析器工厂
 * 根据文件名自动路由到对应的解析器
 * 新增文件格式只需实现DocumentParser接口并加@Component即可自动注册
 */
@Component
public class ParserFactory {

    private final List<DocumentParser> parsers;

    public ParserFactory(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 根据文件名获取对应的解析器
     *
     * @param fileName 文件名（含扩展名）
     * @return 对应的解析器
     * @throws IllegalArgumentException 不支持的文件格式
     */
    public DocumentParser getParser(String fileName) {
        return parsers.stream()
                .filter(p -> p.supports(fileName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的文件格式: " + fileName + "，支持: md/markdown/doc/docx/txt/json(书签文件)"));
    }
}
