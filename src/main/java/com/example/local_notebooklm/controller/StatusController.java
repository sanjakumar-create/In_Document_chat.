package com.example.local_notebooklm.controller;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*") // <--- ADD THIS LINE TO FIX CORS!
@RequestMapping("/api")
public class StatusController {

    @GetMapping("/status")
    public Map<String, String> getStatus() {
        return Map.of(
                "status", "online",
                "generationModel", "gemma2",
                "embeddingModel", "mxbai-embed-large",
                "vectorStore", "ChromaDB",
                "version", "3.0.0"
        );
    }
}
