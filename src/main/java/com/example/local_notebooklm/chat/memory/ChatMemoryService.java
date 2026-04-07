package com.example.local_notebooklm.chat.memory;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ChatMemoryService {

    private static class SessionEntry {
        final ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
        volatile long lastAccessed = System.currentTimeMillis();
    }

    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler;

    public ChatMemoryService() {
        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor();
        this.evictionScheduler.scheduleAtFixedRate(this::evictExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    public ChatMemory getSession(String sessionId) {
        SessionEntry entry = sessions.compute(sessionId, (id, existing) -> {
            if (existing != null) {
                existing.lastAccessed = System.currentTimeMillis();
                return existing;
            }
            return new SessionEntry();
        });
        return entry.memory;
    }

    public void clearHistory(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) {
            entry.memory.clear();
        }
        System.out.println("[Session] Cleared: " + sessionId);
    }

    private void evictExpiredSessions() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().lastAccessed < cutoff);
        System.out.printf("[Sessions] Evicted %d idle sessions. Active: %d%n",
                before - sessions.size(), sessions.size());
    }

    @PreDestroy
    public void shutdown() {
        evictionScheduler.shutdown();
    }
}