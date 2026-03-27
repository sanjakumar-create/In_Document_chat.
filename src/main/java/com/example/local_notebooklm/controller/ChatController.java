package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.dto.ChatResponse;
import com.example.local_notebooklm.service.ChatbotService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatbotService chatbotService;

    public ChatController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /**
     * Run the full CRAG pipeline for a question, optionally scoped to one or more documents.
     *
     * Multi-file support:
     *   filenames=A.pdf,B.pdf  → query those two files (ChromaDB $in filter)
     *   filenames=ALL          → query ALL ingested documents (no filter)
     *   filename=A.pdf         → legacy single-file param (backward compatible)
     *   (neither provided)     → queries ALL documents
     *
     * sessionId: UUID from crypto.randomUUID() generated once per browser tab.
     * Each tab has its own isolated 20-message conversation window.
     *
     * GET /api/chat/ask?question=...&filenames=A.pdf,B.pdf&sessionId=UUID&minScore=0.70&maxResults=10
     */
    @GetMapping("/ask")
    public ChatResponse askAI(
            @RequestParam String question,
            @RequestParam(required = false) String filename,     // legacy — kept for backward compat
            @RequestParam(required = false) String filenames,    // new: "A.pdf,B.pdf" or "ALL"
            @RequestParam(required = false, defaultValue = "default-session") String sessionId,
            @RequestParam(defaultValue = "0.30") double minScore,
            @RequestParam(defaultValue = "10")   int maxResults) {

        // Resolve filenames: new param wins over legacy; null resolved → ALL (no filter)
        List<String> resolved = resolveFilenames(filename, filenames);
        return chatbotService.askAdvancedQuestion(question, minScore, maxResults, resolved, sessionId);
    }

    /**
     * Clear conversation memory for a specific session.
     * POST /api/chat/clear?sessionId=UUID
     */
    @PostMapping("/clear")
    public String clearHistory(
            @RequestParam(required = false, defaultValue = "default-session") String sessionId) {
        chatbotService.clearHistory(sessionId);
        return "Conversation memory cleared for session: " + sessionId;
    }

    /**
     * Resolves the filenames to query:
     * - "ALL" or empty → null (no filter, searches all documents)
     * - "A.pdf,B.pdf"  → List["A.pdf", "B.pdf"]
     * - legacy filename → List[filename]
     */
    private List<String> resolveFilenames(String filename, String filenames) {
        if (filenames != null && !filenames.isBlank()) {
            if ("ALL".equalsIgnoreCase(filenames.trim())) {
                return null;  // null = no ChromaDB filter = search all documents
            }
            List<String> list = Arrays.stream(filenames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
            return list.isEmpty() ? null : list;
        }
        if (filename != null && !filename.isBlank()) {
            return List.of(filename.trim());
        }
        return null;  // neither provided → ALL
    }
}
