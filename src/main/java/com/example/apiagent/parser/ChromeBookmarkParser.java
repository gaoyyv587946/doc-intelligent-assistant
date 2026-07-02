package com.example.apiagent.parser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Chrome书签文件解析器
 * 
 * Chrome书签文件位置：
 * - Windows: C:\Users\{username}\AppData\Local\Google\Chrome\User Data\Default\Bookmarks
 * - Mac: ~/Library/Application Support/Google/Chrome/Default/Bookmarks
 * - Linux: ~/.config/google-chrome/Default/Bookmarks
 * 
 * 注意：Chrome书签文件没有扩展名，文件名就是 "Bookmarks"
 */
@Component
public class ChromeBookmarkParser implements DocumentParser {

    private static final Logger log = LoggerFactory.getLogger(ChromeBookmarkParser.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Document> parse(InputStream inputStream, Metadata metadata) {
        try {
            // 读取并预处理JSON内容
            String jsonContent = readAndCleanJson(inputStream);
            
            // 验证是否为Chrome书签格式
            if (!jsonContent.contains("\"roots\"") || !jsonContent.contains("\"bookmark_bar\"")) {
                throw new RuntimeException("不是有效的Chrome书签文件：缺少roots或bookmark_bar字段");
            }
            
            // 解析JSON
            Map<String, Object> root = objectMapper.readValue(jsonContent, 
                    new TypeReference<Map<String, Object>>() {});
            
            List<BookmarkItem> bookmarks = new ArrayList<>();
            
            // 解析各个根目录
            Map<String, Object> roots = getMap(root, "roots");
            if (roots != null) {
                parseRootFolder(roots, "bookmark_bar", "书签栏", bookmarks);
                parseRootFolder(roots, "other", "其他书签", bookmarks);
                parseRootFolder(roots, "synced", "移动设备书签", bookmarks);
            }
            
            if (bookmarks.isEmpty()) {
                log.warn("Chrome书签文件解析成功，但未找到任何URL书签");
            } else {
                log.info("解析Chrome书签完成，共 {} 个URL书签", bookmarks.size());
            }
            
            // 为每个书签生成独立的Document，方便检索时获取URL
            List<Document> documents = new ArrayList<>();
            String sourceFile = metadata.getString("file_name");
            
            for (BookmarkItem bookmark : bookmarks) {
                String content = String.format(
                        "### %s\n- URL: %s\n- 描述: %s\n- 分类: %s",
                        bookmark.name, bookmark.url, bookmark.name, bookmark.folderPath);
                
                Metadata docMetadata = new Metadata();
                docMetadata.put("file_name", sourceFile);
                docMetadata.put("title", bookmark.name);
                docMetadata.put("url", bookmark.url);
                docMetadata.put("folder", bookmark.folderPath);
                docMetadata.put("type", "bookmark");
                
                documents.add(Document.from(content, docMetadata));
            }
            
            return documents;
            
        } catch (Exception e) {
            log.error("解析Chrome书签文件失败: {}", e.getMessage(), e);
            throw new RuntimeException("解析Chrome书签文件失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        // Chrome书签文件名通常为 "Bookmarks" 或 "Bookmarks.json"
        return lower.equals("bookmarks") || 
               lower.equals("bookmarks.json") ||
               lower.endsWith("_bookmarks.json") ||
               lower.contains("chrome_bookmarks") ||
               fileName.contains("书签");
    }

    /**
     * 读取并清理JSON内容
     */
    private String readAndCleanJson(InputStream inputStream) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = inputStream.read(buffer)) != -1) {
            result.write(buffer, 0, length);
        }
        
        String content = result.toString(StandardCharsets.UTF_8.name());
        
        // 移除BOM头 (UTF-8 BOM: EF BB BF)
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
            log.debug("已移除UTF-8 BOM头");
        }
        
        // 移除行注释
        StringBuilder cleaned = new StringBuilder();
        boolean inString = false;
        boolean escape = false;
        
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            
            if (escape) {
                cleaned.append(c);
                escape = false;
                continue;
            }
            
            if (c == '\\' && inString) {
                cleaned.append(c);
                escape = true;
                continue;
            }
            
            if (c == '"') {
                inString = !inString;
                cleaned.append(c);
                continue;
            }
            
            // 跳过行注释
            if (!inString && c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                while (i < content.length() && content.charAt(i) != '\n') {
                    i++;
                }
                if (i < content.length()) {
                    cleaned.append('\n');
                }
                continue;
            }
            
            cleaned.append(c);
        }
        
        return cleaned.toString().trim();
    }

    /**
     * 解析根目录下的书签
     */
    private void parseRootFolder(Map<String, Object> roots, String folderKey, 
                                  String folderName, List<BookmarkItem> bookmarks) {
        Map<String, Object> folder = getMap(roots, folderKey);
        if (folder != null) {
            parseBookmarkNode(folder, folderName, bookmarks);
        }
    }

    /**
     * 安全获取Map类型的字段值
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return null;
    }

    /**
     * 安全获取String类型的字段值
     */
    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    /**
     * 安全获取List类型的字段值
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return null;
    }

    /**
     * 递归解析书签节点
     */
    private void parseBookmarkNode(Map<String, Object> node, String folderPath, List<BookmarkItem> bookmarks) {
        String type = getString(node, "type");
        String name = getString(node, "name");
        
        if ("url".equals(type)) {
            String url = getString(node, "url");
            if (!url.isEmpty() && url.startsWith("http")) {
                bookmarks.add(new BookmarkItem(name, url, folderPath));
            }
        } else if ("folder".equals(type)) {
            String newFolderPath = folderPath.isEmpty() ? name : folderPath + " / " + name;
            List<Map<String, Object>> children = getList(node, "children");
            if (children != null) {
                for (Map<String, Object> child : children) {
                    parseBookmarkNode(child, newFolderPath, bookmarks);
                }
            }
        }
    }

    /**
     * 书签项数据类
     */
    private static class BookmarkItem {
        final String name;
        final String url;
        final String folderPath;

        BookmarkItem(String name, String url, String folderPath) {
            this.name = name;
            this.url = url;
            this.folderPath = folderPath;
        }
    }
}
