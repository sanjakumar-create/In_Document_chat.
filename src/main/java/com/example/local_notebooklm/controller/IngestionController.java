package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequestMapping("/api/documents")
public class IngestionController {

    private final DocumentIngestionService ingestionService;

    // Spring injects your service here
    public IngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * POST endpoint to trigger file ingestion.
     * Example URL: http://localhost:8080/api/documents/ingest?filePath=/Users/student/Downloads/sample.pdf
     */
    @PostMapping("/ingest")
    public String ingestDocument(@RequestParam String filePath) {
        try {
            Path path = Paths.get(filePath);

            // Call the service we built earlier!
            ingestionService.ingestFile(path);

            return "✅ Successfully ingested and vectorized: " + path.getFileName();
        } catch (Exception e) {
            return "❌ Error ingesting file: " + e.getMessage();
        }
    }
}