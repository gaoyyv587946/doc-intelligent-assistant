package com.example.apiagent.agent.tools;

import com.example.apiagent.service.ProjectDocGeneratorService;
import com.example.apiagent.service.ProjectScannerService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 项目扫描文档生成工具
 *
 * LangChain4j @Tool，供 LLM Agent 调用。
 * 功能：扫描指定项目的源码，由 LLM 生成技术文档，并入库到向量库和关键词索引。
 *
 * 权限控制：仅 admin 用户可用，通过 AdminContext（ThreadLocal）判断。
 * 支持路径：本地绝对路径、Git 仓库 URL、SVN 仓库 URL。
 *
 * 完整流程：
 * 1. 权限检查（AdminContext）
 * 2. 获取项目源码到本地（本地直接读取 / Git clone / SVN checkout）
 * 3. 遍历目录，收集源码，构建项目上下文
 * 4. LLM 生成 Markdown 技术文档
 * 5. 保存到本地 docs 目录 + 向量库 + 关键词索引
 * 6. 清理临时目录
 */
@Component
public class ProjectToMdTool {

    private static final Logger logger = LoggerFactory.getLogger(ProjectToMdTool.class);

    private final ProjectScannerService projectScannerService;
    private final ProjectDocGeneratorService projectDocGeneratorService;

    public ProjectToMdTool(ProjectScannerService projectScannerService,
                           ProjectDocGeneratorService projectDocGeneratorService) {
        this.projectScannerService = projectScannerService;
        this.projectDocGeneratorService = projectDocGeneratorService;
    }

    @Tool("扫描指定项目源码并生成技术文档入库。支持本地路径、Git/SVN仓库URL。仅管理员可用。")
    public String scanProjectAndGenerateDoc(
            @P("项目路径：本地绝对路径(如D:/projects/myapp)、Git仓库URL(如https://github.com/user/repo.git)或SVN地址(如svn://host/repo)") String path,
            @P("项目名称，用于生成文档标题和文件名，如'用户管理系统'、'订单服务'") String projectName) {

        // 第1步：权限检查
        if (!AdminContext.get()) {
            logger.warn("非管理员用户尝试调用项目扫描工具，已拒绝");
            return "没有权限。项目扫描文档生成功能仅限管理员使用，请联系管理员操作。";
        }

        // 第2步：参数校验
        if (path == null || path.isBlank()) {
            return "项目路径不能为空。请提供本地绝对路径、Git 仓库 URL 或 SVN 地址。";
        }
        if (projectName == null || projectName.isBlank()) {
            projectName = "未命名项目";
        }

        logger.info("管理员开始扫描项目: path={}, projectName={}", path, projectName);

        Path projectRoot = null;
        String originalInput = path;

        try {
            // 第3步：获取项目源码到本地
            projectRoot = projectScannerService.resolveProjectSource(path);
            logger.info("项目源码已就绪，根目录: {}", projectRoot);

            // 第4步：构建项目上下文（目录树 + 源码内容）
            String projectContext = projectScannerService.buildProjectContext(projectRoot);
            logger.info("项目上下文构建完成，长度: {} 字符", projectContext.length());

            if (projectContext.length() < 100) {
                return "项目目录为空或未找到可读的源码文件。请确认：\n"
                        + "1. 路径指向正确的项目根目录\n"
                        + "2. 项目中包含源码文件（.java/.py/.js/.ts 等）\n"
                        + "3. 项目不是空目录";
            }

            // 第5步：LLM 生成文档 + 入库
            String result = projectDocGeneratorService.generateAndIngest(projectName, projectContext);
            logger.info("项目文档生成入库完成: {}", projectName);

            return result;

        } catch (Exception e) {
            logger.error("项目扫描失败: path={}, error={}", path, e.getMessage(), e);
            return "项目扫描失败: " + e.getMessage() + "\n"
                    + "请检查：\n"
                    + "1. 路径是否正确且可访问\n"
                    + "2. Git/SVN 地址是否有效\n"
                    + "3. 系统是否已安装 git/svn 命令行工具\n"
                    + "4. 网络连接是否正常";

        } finally {
            // 第6步：清理临时目录（Git clone / SVN checkout 创建的）
            if (projectRoot != null && projectScannerService.isTempDir(projectRoot, originalInput)) {
                projectScannerService.cleanupTempDir(projectRoot);
            }
        }
    }
}

