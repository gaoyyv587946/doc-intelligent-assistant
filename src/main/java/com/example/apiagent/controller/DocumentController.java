package com.example.apiagent.controller;

import com.example.apiagent.service.DocumentService;
import com.example.apiagent.service.DocumentService.DocumentInfo;
import com.example.apiagent.service.DocumentService.DocumentUploadResult;
import com.example.apiagent.service.KeywordIndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档管理接口
 * 提供文档上传、列表、删除和重新加载功能
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final KeywordIndexService keywordIndexService;
    private final boolean defaultUseFormat;

    // 任务状态存储
    private final ConcurrentHashMap<String, TaskStatus> taskStatusMap = new ConcurrentHashMap<>();

    public DocumentController(
            DocumentService documentService,
            KeywordIndexService keywordIndexService,
            @Value("${rag.use-format:false}") boolean defaultUseFormat) {
        this.documentService = documentService;
        this.keywordIndexService = keywordIndexService;
        this.defaultUseFormat = defaultUseFormat;
    }

    /**
     * 列出所有已索引的文档
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listDocuments() {
        List<DocumentInfo> docs = documentService.listDocuments();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "documents", docs
        ));
    }

    /**
     * 上传文档（异步处理）
     *
     * POST /api/documents/upload
     * Content-Type: multipart/form-data
     * file: <文档文件>
     * useFormat: true/false (可选，是否使用LLM格式化)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "useFormat", required = false) Boolean useFormat) {

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "文件名为空"
            ));
        }

        log.info("收到文档上传请求: {} ({}KB)", fileName, file.getSize() / 1024);

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        boolean formatFlag = useFormat != null ? useFormat : defaultUseFormat;

        taskStatusMap.put(taskId, new TaskStatus("processing", "文档处理中...", 0));

        CompletableFuture.runAsync(() -> {
            try {
                taskStatusMap.put(taskId, new TaskStatus("processing", "解析文档...", 10));

                DocumentUploadResult result = documentService.uploadDocument(file, formatFlag);

                if (result.success()) {
                    taskStatusMap.put(taskId, new TaskStatus(
                            "completed",
                            "文档处理成功",
                            100,
                            result.fileName(),
                            result.fragmentCount()
                    ));
                    log.info("文档处理成功: {} ({}个片段)", result.fileName(), result.fragmentCount());
                } else {
                    taskStatusMap.put(taskId, new TaskStatus("failed", result.message(), 0));
                }
            } catch (Exception e) {
                log.error("文档处理异常: {}", e.getMessage(), e);
                taskStatusMap.put(taskId, new TaskStatus("failed", "处理失败: " + e.getMessage(), 0));
            }
        });

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "文档已接收，正在处理中",
                "taskId", taskId,
                "fileName", fileName
        ));
    }

    /**
     * 查询任务状态
     */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        TaskStatus status = taskStatusMap.get(taskId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("success", true);
        response.put("status", status.status());
        response.put("message", status.message());
        response.put("progress", status.progress());

        if (status.fileName() != null) {
            response.put("savedAs", status.fileName());
            response.put("fragmentCount", status.fragmentCount());
        }

        if ("completed".equals(status.status()) || "failed".equals(status.status())) {
            taskStatusMap.remove(taskId);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * 重新加载本地 docs 目录
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadDocuments() {
        try {
            documentService.loadDefaultDocuments();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文档重新加载完成"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "重新加载失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 重新加载单个文档
     * 
     * POST /api/documents/reload/{fileName}
     */
    @PostMapping("/reload/{fileName}")
    public ResponseEntity<Map<String, Object>> reloadDocument(@PathVariable String fileName) {
        try {
            int fragmentCount = documentService.reloadDocument(fileName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文档 " + fileName + " 重新加载完成",
                    "fragmentCount", fragmentCount
            ));
        } catch (Exception e) {
            log.error("重新加载文档失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "重新加载失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 清除所有向量和索引
     */
    @DeleteMapping("/vectors")
    public ResponseEntity<Map<String, Object>> clearAllVectors() {
        try {
            documentService.clearAllVectors();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "所有向量和索引已清除"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "清除失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 清除指定文档的向量和索引
     */
    @DeleteMapping("/vectors/{fileName}")
    public ResponseEntity<Map<String, Object>> clearDocumentVectors(
            @PathVariable String fileName) {
        try {
            documentService.clearDocumentVectors(fileName);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "文档 " + fileName + " 已清除"
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false,
                    "message", "清除失败: " + e.getMessage()
            ));
        }
    }

    /**
     * 索引统计信息
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        KeywordIndexService.IndexStats stats = keywordIndexService.getStats();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "keywordIndex", Map.of(
                        "docCount", stats.docCount(),
                        "termCount", stats.termCount(),
                        "metadataCount", stats.metadataCount()
                )
        ));
    }

    /**
     * 任务状态记录
     */
    private record TaskStatus(
            String status,
            String message,
            int progress,
            String fileName,
            Integer fragmentCount
    ) {
        TaskStatus(String status, String message, int progress) {
            this(status, message, progress, null, null);
        }
    }
}
