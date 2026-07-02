package com.example.apiagent.service;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 项目文档生成服务
 *
 * 使用 LLM 分析项目源码上下文，生成结构化的技术文档（Markdown），
 * 然后调用 DocumentService 入库（向量库 + 关键词索引）。
 *
 * 流程：
 * 1. 接收 ProjectScannerService 构建的项目上下文
 * 2. 发送给 LLM，要求生成全面的技术文档
 * 3. 调用 DocumentService.ingestGeneratedDoc() 保存到本地 + 入库
 */
@Service
public class ProjectDocGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocGeneratorService.class);

    private final OpenAiChatModel chatModel;
    private final DocumentService documentService;

    public ProjectDocGeneratorService(OpenAiChatModel chatModel, DocumentService documentService) {
        this.chatModel = chatModel;
        this.documentService = documentService;
    }

    /**
     * 生成项目文档并入库
     *
     * @param projectName    项目名称（用于生成文件名和 LLM 提示）
     * @param projectContext 项目源码上下文（目录树 + 源码文件内容）
     * @return 操作结果描述文本
     */
    public String generateAndIngest(String projectName, String projectContext) {
        log.info("开始为项目 [{}] 生成文档，上下文长度: {} 字符", projectName, projectContext.length());

        // 第1步：LLM 生成技术文档
        String markdownDoc;
        try {
            markdownDoc = generateDocWithLLM(projectName, projectContext);
            log.info("LLM 文档生成完成，长度: {} 字符", markdownDoc.length());
        } catch (Exception e) {
            log.error("LLM 文档生成失败，使用原始项目结构作为降级文档: {}", e.getMessage());
            // 降级方案：直接使用原始项目上下文作为文档
            markdownDoc = "# " + projectName + " 项目文档\n\n"
                    + "> 注：LLM 文档生成失败，以下为原始项目结构信息\n\n"
                    + projectContext;
        }

        // 第2步：生成文件名
        String fileName = generateFileName(projectName);

        // 第3步：入库（保存本地 + 向量库 + 关键词索引）
        try {
            int fragmentCount = documentService.ingestGeneratedDoc(fileName, markdownDoc);

            return "项目文档生成并入库成功！\n"
                    + "- 项目名称: " + projectName + "\n"
                    + "- 文档文件名: " + fileName + "\n"
                    + "- 文档长度: " + markdownDoc.length() + " 字符\n"
                    + "- 入库片段数: " + fragmentCount + "\n"
                    + "- 已同步到向量库和关键词索引\n"
                    + "- 现在可以通过对话搜索该项目文档的内容";

        } catch (Exception e) {
            log.error("文档入库失败: {}", e.getMessage(), e);
            return "项目文档已生成，但入库失败: " + e.getMessage()
                    + "\n文档文件名: " + fileName
                    + "\n文档长度: " + markdownDoc.length() + " 字符"
                    + "\n请检查系统日志或重试。";
        }
    }

    /**
     * 调用 LLM 生成项目技术文档
     */
    private String generateDocWithLLM(String projectName, String projectContext) {
        String prompt = """
                你是一个技术文档专家。请根据以下项目源码信息，生成一份全面的技术文档。
                
                项目名称：%s
                
                文档要求（请严格按照以下结构生成）：
                
                ## 项目概述
                项目用途、技术栈、主要功能模块概述。
                
                ## 项目结构
                目录组织说明，各模块/包的职责。
                
                ## 核心模块分析
                每个主要模块/类的功能说明、关键方法描述、类之间的关系。
                
                ## 接口文档（如有 REST API）
                每个接口用 ### 标题，包含：
                - 接口路径：`GET /api/xxx` 或 `POST /api/xxx`
                - 接口描述
                - 请求参数（表格：参数名、类型、是否必填、说明）
                - 返回示例（JSON 代码块）
                - 错误码说明
                
                ## 配置说明
                关键配置项及其含义、默认值。
                
                ## 依赖关系
                主要第三方依赖及用途（表格：依赖名、版本、用途）。
                
                ## 数据模型（如有）
                实体类说明、数据库表结构、字段定义。
                
                格式要求：
                - 使用 Markdown 格式
                - 每个大章节用 ## 标题
                - 接口路径用 `GET /api/xxx` 格式标注
                - 参数用表格展示
                - 代码片段用代码块包裹
                - 基于实际源码内容生成，不要编造不存在的信息
                - 若某章节在源码中无对应内容，可省略该章节
                
                项目源码信息：
                ---
                %s
                ---
                
                请直接输出 Markdown 文档内容，不要添加额外说明。
                """.formatted(projectName, projectContext);

        try {
            String response = chatModel.generate(prompt);
            // 清理可能的 LLM 额外包裹（如 ```markdown ... ```）
            if (response != null && response.startsWith("```markdown")) {
                response = response.replaceAll("^```markdown\\s*", "").replaceAll("```$", "").strip();
            } else if (response != null && response.startsWith("```")) {
                response = response.replaceAll("^```\\s*", "").replaceAll("```$", "").strip();
            }
            return response;
        } catch (Exception e) {
            throw new RuntimeException("LLM 文档生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 生成文档文件名
     */
    private String generateFileName(String projectName) {
        String safeName = projectName.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]", "_");
        if (safeName.isBlank()) safeName = "project";
        return safeName + "_项目文档.md";
    }
}
