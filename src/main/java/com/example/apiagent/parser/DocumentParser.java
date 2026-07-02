package com.example.apiagent.parser;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.io.InputStream;
import java.util.List;

/**
 * 文档解析器接口
 * 不同文件格式有不同的解析实现，通过supports()方法自动路由
 */
public interface DocumentParser {

    /**
     * 解析文档为LangChain4j Document列表
     *
     * @param inputStream 文件输入流
     * @param metadata    文档元数据（包含来源文件名等信息）
     * @return 解析后的Document列表
     */
    List<Document> parse(InputStream inputStream, Metadata metadata);

    /**
     * 判断是否支持该文件格式
     */
    boolean supports(String fileName);
}
