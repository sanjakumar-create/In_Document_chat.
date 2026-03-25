package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.dto.ChatResponse;
import com.example.local_notebooklm.service.ChatbotService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatbotService chatbotService;

    public ChatController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /**
     * Ask the AI a question about the ingested documents.
     * Optional params let the UI override retrieval sensitivity at runtime.
     *
     * GET /api/chat/ask?question=What is the policy?&minScore=0.70&maxResults=5
     */
    @GetMapping("/ask")
    public ChatResponse askAI(
            @RequestParam String question,
            @RequestParam(defaultValue = "0.70") double minScore,
            @RequestParam(defaultValue = "5") int maxResults) {
        return chatbotService.askAdvancedQuestion(question, minScore, maxResults);
    }

    /**
     * Clears the server-side conversational memory window.
     * POST /api/chat/clear
     */
    @PostMapping("/clear")
    public String clearHistory() {
        chatbotService.clearHistory();
        return "✅ Conversation memory cleared";
    }
}
