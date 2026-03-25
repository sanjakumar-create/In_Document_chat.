package com.example.local_notebooklm.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class StatusController {

    @GetMapping("/status")
    public Map<String, String> getStatus() {
        return Map.of(
                "status", "online",
                "generationModel", "llama3",
                "embeddingModel", "nomic-embed-text",
                "vectorStore", "ChromaDB",
                "version", "1.0.0"
        );
    }
}
