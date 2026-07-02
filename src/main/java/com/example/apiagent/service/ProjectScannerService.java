package com.example.apiagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 项目扫描服务
 *
 * 负责将任意来源的项目代码（本地路径、Git URL、SVN URL）解析为结构化的文本上下文，
 * 供 LLM 生成项目技术文档。
 *
 * 功能：
 * 1. 自动识别路径类型（本地/Git/SVN）
 * 2. Git clone / SVN checkout 到临时目录（使用 ProcessBuilder，零额外依赖）
 * 3. 递归遍历目录树，按优先级收集源码文件
 * 4. 构建项目上下文字符串（目录树 + 源码内容）
 *
 * 限制：
 * - 最多读取 50 个文件
 * - 单文件最多 5000 字符
 * - 总上下文最多 50000 字符（约 15000 token）
 */
@Service
public class ProjectScannerService {

    private static final Logger log = LoggerFactory.getLogger(ProjectScannerService.class);

    // ===== 排除的目录名 =====
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", ".svn", "node_modules", "target", "build", "dist",
            ".idea", "__pycache__", ".gradle", "vendor", ".vscode",
            ".next", ".nuxt", "out", "bin", "obj", "coverage",
            ".settings", ".classpath", "META-INF"
    );

    // ===== 排除的文件扩展名 =====
    private static final Set<String> EXCLUDED_EXTENSIONS = Set.of(
            ".class", ".jar", ".war", ".ear", ".exe", ".dll", ".so", ".dylib",
            ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".svg", ".webp",
            ".mp3", ".mp4", ".avi", ".mov", ".zip", ".tar", ".gz", ".rar",
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".lock", ".log", ".map", ".min.js", ".min.css"
    );

    // ===== 高优先级文件名（构建配置、入口文件） =====
    private static final Set<String> HIGH_PRIORITY_FILES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
            "requirements.txt", "go.mod", "Cargo.toml", "Gemfile",
            "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "Makefile", "CMakeLists.txt"
    );

    // ===== 高优先级扩展名（源码） =====
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs",
            ".rb", ".php", ".c", ".cpp", ".h", ".hpp", ".cs", ".kt",
            ".scala", ".swift", ".vue", ".svelte"
    );

    // ===== 配置文件名 =====
    private static final Set<String> CONFIG_FILES = Set.of(
            "application.yml", "application.yaml", "application.properties",
            ".env", ".env.example", "config.json", "config.yml", "config.yaml",
            "settings.json", "tsconfig.json", ".eslintrc", ".prettierrc",
            "webpack.config.js", "vite.config.js", "vite.config.ts",
            "next.config.js", "nginx.conf", "README.md"
    );

    @Value("${project-scan.max-files:50}")
    private int maxFiles;

    @Value("${project-scan.max-file-chars:5000}")
    private int maxFileChars;

    @Value("${project-scan.max-source-chars:50000}")
    private int maxSourceChars;

    @Value("${project-scan.max-tree-depth:3}")
    private int maxTreeDepth;

    @Value("${project-scan.git-timeout:120}")
    private int gitTimeout;

    @Value("${project-scan.svn-timeout:120}")
    private int svnTimeout;

    // ===== 路径类型检测 =====

    private enum SourceType { LOCAL, GIT, SVN }

    private SourceType detectSourceType(String path) {
        String trimmed = path.trim();
        if (trimmed.startsWith("git://") || trimmed.startsWith("git+ssh://")) {
            return SourceType.GIT;
        }
        if ((trimmed.startsWith("https://") || trimmed.startsWith("http://"))
                && (trimmed.endsWith(".git") || trimmed.contains("github.com")
                    || trimmed.contains("gitlab.com") || trimmed.contains("gitee.com")
                    || trimmed.contains("bitbucket.org"))) {
            return SourceType.GIT;
        }
        if (trimmed.startsWith("svn://") || trimmed.startsWith("svn+ssh://")
                || trimmed.startsWith("svn+http://") || trimmed.startsWith("svn+https://")) {
            return SourceType.SVN;
        }
        return SourceType.LOCAL;
    }

    // ===== 项目获取 =====

    /**
     * 将项目源码获取到本地路径（本地直接返回，Git/SVN clone/checkout 到临时目录）
     *
     * @param path 项目路径（本地绝对路径、Git URL 或 SVN URL）
     * @return 项目根目录 Path
     * @throws IOException 获取失败
     */
    public Path resolveProjectSource(String path) throws IOException {
        SourceType type = detectSourceType(path);
        log.info("检测到项目来源类型: {}, 路径: {}", type, path);

        return switch (type) {
            case LOCAL -> resolveLocalPath(path);
            case GIT -> cloneGitRepo(path);
            case SVN -> checkoutSvn(path);
        };
    }

    private Path resolveLocalPath(String path) throws IOException {
        Path localPath = Path.of(path).toAbsolutePath().normalize();
        if (!Files.exists(localPath)) {
            throw new IOException("本地路径不存在: " + localPath);
        }
        if (!Files.isDirectory(localPath)) {
            throw new IOException("路径不是目录: " + localPath);
        }
        log.info("使用本地项目路径: {}", localPath);
        return localPath;
    }

    private Path cloneGitRepo(String url) throws IOException {
        Path tempDir = Files.createTempDirectory("project-scan-git-");
        log.info("正在 Git clone 到临时目录: {}", tempDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--depth", "1", "--single-branch", url, tempDir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 读取输出（防止进程阻塞）
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(gitTimeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Git clone 超时（" + gitTimeout + "秒），请检查网络或仓库大小");
            }

            if (process.exitValue() != 0) {
                throw new IOException("Git clone 失败（退出码 " + process.exitValue() + "）: " + output);
            }

            log.info("Git clone 成功: {}", url);
            return tempDir;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git clone 被中断", e);
        } catch (IOException e) {
            // 清理临时目录
            cleanupTempDir(tempDir);
            throw e;
        }
    }

    private Path checkoutSvn(String url) throws IOException {
        Path tempDir = Files.createTempDirectory("project-scan-svn-");
        log.info("正在 SVN checkout 到临时目录: {}", tempDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "svn", "checkout", "--depth", "infinity", url, tempDir.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(svnTimeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("SVN checkout 超时（" + svnTimeout + "秒）");
            }

            if (process.exitValue() != 0) {
                throw new IOException("SVN checkout 失败（退出码 " + process.exitValue() + "）: " + output);
            }

            log.info("SVN checkout 成功: {}", url);
            return tempDir;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SVN checkout 被中断", e);
        } catch (IOException e) {
            cleanupTempDir(tempDir);
            throw e;
        }
    }

    // ===== 项目上下文构建 =====

    /**
     * 构建项目上下文：目录树结构 + 关键源码文件内容
     *
     * @param projectRoot 项目根目录
     * @return 项目上下文字符串（供 LLM 使用）
     */
    public String buildProjectContext(Path projectRoot) throws IOException {
        StringBuilder context = new StringBuilder();

        // 第1步：生成目录树
        context.append("## 项目目录结构\n```\n");
        context.append(generateDirectoryTree(projectRoot, "", 0));
        context.append("```\n\n");

        // 第2步：收集并读取源码文件
        List<Path> sourceFiles = collectSourceFiles(projectRoot);
        log.info("收集到 {} 个源码文件（按优先级排序）", sourceFiles.size());

        context.append("## 关键源码文件\n\n");

        int totalChars = 0;
        int fileCount = 0;

        for (Path file : sourceFiles) {
            if (fileCount >= maxFiles) break;

            String content = readFileContent(file, maxFileChars);
            if (totalChars + content.length() > maxSourceChars) break;

            String relativePath = projectRoot.relativize(file).toString().replace('\\', '/');
            String ext = getFileExtension(file);

            context.append("### ").append(relativePath).append("\n");
            context.append("```").append(ext).append("\n");
            context.append(content).append("\n```\n\n");

            totalChars += content.length();
            fileCount++;
        }

        log.info("项目上下文构建完成: {} 个文件, {} 字符", fileCount, totalChars);
        return context.toString();
    }

    /**
     * 递归生成目录树字符串
     */
    private String generateDirectoryTree(Path dir, String prefix, int depth) {
        if (depth >= maxTreeDepth) return "";

        StringBuilder tree = new StringBuilder();
        try {
            List<Path> entries = Files.list(dir)
                    .sorted(Comparator.comparing(p -> {
                        boolean isDir = Files.isDirectory(p);
                        return (isDir ? "0" : "1") + p.getFileName().toString().toLowerCase();
                    }))
                    .toList();

            for (int i = 0; i < entries.size(); i++) {
                Path entry = entries.get(i);
                String name = entry.getFileName().toString();

                // 跳过排除的目录
                if (Files.isDirectory(entry) && EXCLUDED_DIRS.contains(name)) continue;
                // 跳过隐藏文件/目录（以.开头）
                if (name.startsWith(".") && !name.equals(".env")) continue;

                boolean isLast = (i == entries.size() - 1);
                String connector = isLast ? "└── " : "├── ";
                String childPrefix = isLast ? "    " : "│   ";

                if (Files.isDirectory(entry)) {
                    tree.append(prefix).append(connector).append(name).append("/\n");
                    tree.append(generateDirectoryTree(entry, prefix + childPrefix, depth + 1));
                } else {
                    tree.append(prefix).append(connector).append(name).append("\n");
                }
            }
        } catch (IOException e) {
            tree.append(prefix).append("[无法读取目录]\n");
        }
        return tree.toString();
    }

    /**
     * 收集源码文件，按优先级排序
     * 优先级：构建配置 > 入口文件 > 核心源码 > 配置文件 > 其他
     */
    private List<Path> collectSourceFiles(Path projectRoot) throws IOException {
        List<Path> highPriority = new ArrayList<>();   // 构建配置
        List<Path> sourceFiles = new ArrayList<>();    // 核心源码
        List<Path> configFiles = new ArrayList<>();    // 配置文件
        List<Path> otherFiles = new ArrayList<>();     // 其他可读文件

        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName().toString();
                if (EXCLUDED_DIRS.contains(name) || (name.startsWith(".") && !name.equals(".env"))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.size() > 100 * 1024) return FileVisitResult.CONTINUE; // 跳过 >100KB

                String name = file.getFileName().toString();
                String ext = getFileExtension(file);

                if (EXCLUDED_EXTENSIONS.contains(ext)) return FileVisitResult.CONTINUE;

                if (HIGH_PRIORITY_FILES.contains(name)) {
                    highPriority.add(file);
                } else if (SOURCE_EXTENSIONS.contains(ext)) {
                    sourceFiles.add(file);
                } else if (CONFIG_FILES.contains(name)) {
                    configFiles.add(file);
                } else if (isReadableTextFile(file)) {
                    otherFiles.add(file);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        // 源码文件按路径深度排序（浅层优先）
        sourceFiles.sort(Comparator.comparingInt(p -> p.getNameCount()));

        // 合并：构建配置 > 配置文件 > 核心源码（浅层优先） > 其他
        List<Path> result = new ArrayList<>();
        result.addAll(highPriority);
        result.addAll(configFiles);
        result.addAll(sourceFiles);
        result.addAll(otherFiles);

        return result;
    }

    // ===== 工具方法 =====

    private String readFileContent(Path file, int maxChars) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.length() > maxChars) {
                return content.substring(0, maxChars) + "\n... [截断，原文 " + content.length() + " 字符]";
            }
            return content;
        } catch (IOException e) {
            return "[读取失败: " + e.getMessage() + "]";
        }
    }

    private String getFileExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "";
    }

    private boolean isReadableTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".yml")
                || name.endsWith(".yaml") || name.endsWith(".properties") || name.endsWith(".xml")
                || name.endsWith(".json") || name.endsWith(".toml") || name.endsWith(".ini")
                || name.endsWith(".cfg") || name.endsWith(".conf") || name.endsWith(".sh")
                || name.endsWith(".bat") || name.endsWith(".sql");
    }

    /**
     * 清理临时目录（递归删除）
     * 应在 finally 块中调用
     */
    public void cleanupTempDir(Path tempDir) {
        if (tempDir == null) return;
        try {
            Files.walkFileTree(tempDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            log.info("临时目录已清理: {}", tempDir);
        } catch (IOException e) {
            log.warn("清理临时目录失败: {}: {}", tempDir, e.getMessage());
        }
    }

    /**
     * 判断路径是否为临时目录（Git/SVN clone 的）
     */
    public boolean isTempDir(Path path, String originalInput) {
        return path != null && path.toString().contains("project-scan-")
                && !originalInput.trim().equals(path.toString());
    }
}
