package com.example.apiagent.controller;

import com.example.apiagent.service.NoteService;
import com.example.apiagent.service.NoteService.Note;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库笔记管理接口
 *
 * 支持自定义内容录入（如错误信息、经验总结等），
 * 录入后自动同步到向量库和关键词索引，可被检索。
 *
 * API:
 * POST   /api/notes          - 创建笔记
 * GET    /api/notes           - 列出所有笔记
 * GET    /api/notes/{id}      - 获取单条笔记
 * PUT    /api/notes/{id}      - 更新笔记
 * DELETE /api/notes/{id}      - 删除笔记
 * GET    /api/notes/filenames - 获取可用文件名列表
 */
@RestController
@RequestMapping("/api/notes")
public class NoteController {

    private static final Logger log = LoggerFactory.getLogger(NoteController.class);

    private final NoteService noteService;

    public NoteController(NoteService noteService) {
        this.noteService = noteService;
    }

    /**
     * 创建笔记
     *
     * POST /api/notes
     * Body: { "fileName": "xxx.md", "title": "...", "content": "...", "tags": ["tag1"] }
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createNote(@RequestBody NoteRequest request) {
        if (request.fileName() == null || request.fileName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "文件名不能为空"));
        }
        if (request.title() == null || request.title().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "标题不能为空"));
        }
        if (request.content() == null || request.content().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "内容不能为空"));
        }

        try {
            Note note = noteService.createNote(
                    request.fileName(),
                    request.title(),
                    request.content(),
                    request.tags());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "笔记创建成功",
                    "note", noteToMap(note)));
        } catch (Exception e) {
            log.error("创建笔记失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "创建失败: " + e.getMessage()));
        }
    }

    /**
     * 列出所有笔记
     *
     * GET /api/notes
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listNotes() {
        try {
            List<Note> notes = noteService.listNotes();
            List<Map<String, Object>> noteList = notes.stream()
                    .map(this::noteToMap)
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "notes", noteList,
                    "total", noteList.size()));
        } catch (Exception e) {
            log.error("获取笔记列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "获取列表失败: " + e.getMessage()));
        }
    }

    /**
     * 获取单条笔记
     *
     * GET /api/notes/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getNote(@PathVariable String id) {
        try {
            Note note = noteService.getNote(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "note", noteToMap(note)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取笔记失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "获取失败: " + e.getMessage()));
        }
    }

    /**
     * 更新笔记
     *
     * PUT /api/notes/{id}
     * Body: { "fileName": "xxx.md", "title": "...", "content": "...", "tags": ["tag1"] }
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateNote(
            @PathVariable String id, @RequestBody NoteRequest request) {
        try {
            Note note = noteService.updateNote(
                    id,
                    request.fileName(),
                    request.title(),
                    request.content(),
                    request.tags());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "笔记更新成功",
                    "note", noteToMap(note)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("更新笔记失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "更新失败: " + e.getMessage()));
        }
    }

    /**
     * 删除笔记
     *
     * DELETE /api/notes/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteNote(@PathVariable String id) {
        try {
            noteService.deleteNote(id);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "笔记已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("删除笔记失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "删除失败: " + e.getMessage()));
        }
    }

    /**
     * 获取可用文件名列表
     *
     * GET /api/notes/filenames
     */
    @GetMapping("/filenames")
    public ResponseEntity<Map<String, Object>> getFileNames() {
        try {
            Set<String> fileNames = noteService.getAvailableFileNames();
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "fileNames", fileNames));
        } catch (Exception e) {
            log.error("获取文件名列表失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "message", "获取失败: " + e.getMessage()));
        }
    }

    // ==================== 工具方法 ====================

    private Map<String, Object> noteToMap(Note note) {
        return Map.of(
                "id", note.id(),
                "fileName", note.fileName(),
                "title", note.title(),
                "content", note.content(),
                "tags", note.tags(),
                "createdAt", note.createdAt(),
                "updatedAt", note.updatedAt());
    }

    /**
     * 请求体
     */
    public record NoteRequest(
            String fileName,
            String title,
            String content,
            List<String> tags
    ) {}
}
