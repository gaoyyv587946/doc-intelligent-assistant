package com.example.apiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库笔记服务
 *
 * 支持自定义录入内容（如错误信息、经验总结等），
 * 录入后自动同步到向量库和关键词索引，支持检索。
 *
 * 存储方案：
 * - 元数据：notes/notes-index.json（所有笔记的索引）
 * - 内容：notes/{id}.md（每条笔记的markdown文件）
 * - 同时同步到向量库和关键词索引
 */
@Service
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final EmbeddingStoreIngestor ingestor;
    private final InMemoryEmbeddingStore<TextSegment> embeddingStore;
    private final KeywordIndexService keywordIndexService;
    private final String notesPath;
    private final ObjectMapper objectMapper;

    /** 内存索引：noteId -> Note */
    private final ConcurrentHashMap<String, Note> noteIndex = new ConcurrentHashMap<>();

    public NoteService(
            EmbeddingStoreIngestor ingestor,
            InMemoryEmbeddingStore<TextSegment> embeddingStore,
            KeywordIndexService keywordIndexService,
            @Value("${notes.storage-path:notes}") String notesPath) {
        this.ingestor = ingestor;
        this.embeddingStore = embeddingStore;
        this.keywordIndexService = keywordIndexService;
        this.notesPath = notesPath;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void loadExistingNotes() {
        try {
            Path dir = Path.of(notesPath);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("笔记目录不存在，已创建: {}", notesPath);
                return;
            }

            Path indexFile = dir.resolve("notes-index.json");
            if (!Files.exists(indexFile)) {
                log.info("笔记索引文件不存在，跳过加载");
                return;
            }

            List<Note> notes = objectMapper.readValue(
                    indexFile.toFile(), new TypeReference<>() {});
            for (Note note : notes) {
                noteIndex.put(note.id(), note);
            }

            // 将已有笔记同步到向量库和关键词索引
            for (Note note : notes) {
                try {
                    ingestNoteToStores(note);
                } catch (Exception e) {
                    log.warn("笔记 {} 同步到索引失败: {}", note.id(), e.getMessage());
                }
            }

            log.info("已加载 {} 条笔记", notes.size());
        } catch (Exception e) {
            log.warn("加载笔记失败: {}", e.getMessage());
        }
    }

    // ==================== CRUD ====================

    /**
     * 创建笔记
     */
    public Note createNote(String fileName, String title, String content, List<String> tags) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String now = LocalDateTime.now().format(DATETIME_FMT);

        Note note = new Note(id, fileName, title, content,
                tags != null ? tags : Collections.emptyList(), now, now);

        noteIndex.put(id, note);
        saveNoteFile(note);
        saveIndexFile();
        ingestNoteToStores(note);

        log.info("笔记已创建: id={}, title={}", id, title);
        return note;
    }

    /**
     * 更新笔记
     */
    public Note updateNote(String id, String fileName, String title, String content, List<String> tags) {
        Note existing = noteIndex.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }

        // 先从索引中移除旧数据
        removeFromStores(id, existing.fileName());

        String now = LocalDateTime.now().format(DATETIME_FMT);
        Note updated = new Note(id,
                fileName != null ? fileName : existing.fileName(),
                title != null ? title : existing.title(),
                content != null ? content : existing.content(),
                tags != null ? tags : existing.tags(),
                existing.createdAt(),
                now);

        noteIndex.put(id, updated);
        saveNoteFile(updated);
        saveIndexFile();
        ingestNoteToStores(updated);

        log.info("笔记已更新: id={}, title={}", id, updated.title());
        return updated;
    }

    /**
     * 删除笔记
     */
    public void deleteNote(String id) {
        Note existing = noteIndex.remove(id);
        if (existing == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }

        removeFromStores(id, existing.fileName());
        deleteNoteFile(id);
        saveIndexFile();

        log.info("笔记已删除: id={}", id);
    }

    /**
     * 获取所有笔记（按创建时间倒序）
     */
    public List<Note> listNotes() {
        return noteIndex.values().stream()
                .sorted(Comparator.comparing(Note::createdAt).reversed())
                .toList();
    }

    /**
     * 获取单条笔记
     */
    public Note getNote(String id) {
        Note note = noteIndex.get(id);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在: " + id);
        }
        return note;
    }

    /**
     * 获取所有已存在的文件名列表（docs目录 + notes中的文件名）
     */
    public Set<String> getAvailableFileNames() {
        Set<String> fileNames = new LinkedHashSet<>();

        // docs目录中的文件
        Path docsDir = Path.of("docs");
        if (Files.exists(docsDir)) {
            try {
                Files.list(docsDir)
                        .filter(p -> !Files.isDirectory(p))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .forEach(fileNames::add);
            } catch (IOException e) {
                log.warn("读取docs目录失败: {}", e.getMessage());
            }
        }

        // notes中使用的fileName
        noteIndex.values().stream()
                .map(Note::fileName)
                .filter(Objects::nonNull)
                .sorted()
                .forEach(fileNames::add);

        return fileNames;
    }

    // ==================== 持久化 ====================

    private void saveIndexFile() {
        try {
            Path dir = Path.of(notesPath);
            Files.createDirectories(dir);
            Path indexFile = dir.resolve("notes-index.json");
            objectMapper.writeValue(indexFile.toFile(), new ArrayList<>(noteIndex.values()));
        } catch (IOException e) {
            log.error("保存笔记索引失败: {}", e.getMessage(), e);
        }
    }

    private void saveNoteFile(Note note) {
        try {
            Path dir = Path.of(notesPath);
            Files.createDirectories(dir);
            Path noteFile = dir.resolve(note.id() + ".md");
            String mdContent = noteToMarkdown(note);
            Files.writeString(noteFile, mdContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("保存笔记文件失败: {}", e.getMessage(), e);
        }
    }

    private void deleteNoteFile(String id) {
        try {
            Path noteFile = Path.of(notesPath, id + ".md");
            Files.deleteIfExists(noteFile);
        } catch (IOException e) {
            log.warn("删除笔记文件失败: {}", e.getMessage());
        }
    }

    // ==================== 向量/索引同步 ====================

    /**
     * 将笔记同步到向量库和关键词索引
     */
    private void ingestNoteToStores(Note note) {
        try {
            String mdContent = noteToMarkdown(note);
            Metadata metadata = new Metadata();
            metadata.put("file_name", note.fileName());
            metadata.put("note_id", note.id());
            metadata.put("title", note.title());
            metadata.put("source_type", "note");
            if (!note.tags().isEmpty()) {
                metadata.put("tags", String.join(",", note.tags()));
            }

            Document doc = Document.from(mdContent, metadata);
            ingestor.ingest(List.of(doc));

            // 同步到关键词索引
            String docId = note.fileName() + "#note_" + note.id();
            Map<String, String> kwMetadata = new HashMap<>();
            kwMetadata.put("file_name", note.fileName());
            kwMetadata.put("title", note.title());
            kwMetadata.put("source_type", "note");
            kwMetadata.put("note_id", note.id());
            if (!note.tags().isEmpty()) {
                kwMetadata.put("tags", String.join(",", note.tags()));
            }

            keywordIndexService.addDocument(docId, mdContent, kwMetadata);
            log.debug("笔记已同步到索引: {}", note.id());
        } catch (Exception e) {
            log.warn("笔记同步到索引失败: {}: {}", note.id(), e.getMessage());
        }
    }

    /**
     * 从向量库和关键词索引中移除笔记
     */
    private void removeFromStores(String noteId, String fileName) {
        try {
            // 从向量库移除
            IsEqualTo filter = new IsEqualTo("note_id", noteId);
            embeddingStore.removeAll(filter);

            // 从关键词索引移除
            String docId = fileName + "#note_" + noteId;
            keywordIndexService.removeDocument(docId);

            log.debug("笔记已从索引移除: {}", noteId);
        } catch (Exception e) {
            log.warn("笔记从索引移除失败: {}: {}", noteId, e.getMessage());
        }
    }

    // ==================== Markdown转换 ====================

    private String noteToMarkdown(Note note) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(note.title()).append("\n\n");

        if (!note.tags().isEmpty()) {
            sb.append("标签：").append(String.join(", ", note.tags())).append("\n\n");
        }

        sb.append(note.content()).append("\n");
        return sb.toString();
    }

    // ==================== 数据类 ====================

    /**
     * 笔记数据
     */
    public record Note(
            String id,
            String fileName,
            String title,
            String content,
            List<String> tags,
            String createdAt,
            String updatedAt
    ) {}
}

