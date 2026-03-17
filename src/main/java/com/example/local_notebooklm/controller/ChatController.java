package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.dto.ChatResponse;
import com.example.local_notebooklm.service.ChatbotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatbotService chatbotService;

    public ChatController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    @GetMapping("/ask")
    public ChatResponse askAI(@RequestParam String question) {
        // Spring Boot automatically converts the ChatResponse object into JSON!
        return chatbotService.askAdvancedQuestion(question);
    }
}