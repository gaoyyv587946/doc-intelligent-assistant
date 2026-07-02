package com.example.apiagent.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存聊天记忆存储
 * 按sessionId隔离存储ChatMemory，不同用户会话互不干扰
 *
 * 注意：这是内存实现，应用重启后记忆丢失
 * 生产环境应替换为Redis或数据库实现
 */
@Component
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentHashMap<String, List<ChatMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return store.getOrDefault(memoryId.toString(), new ArrayList<>());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        store.put(memoryId.toString(), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        store.remove(memoryId.toString());
    }
}
