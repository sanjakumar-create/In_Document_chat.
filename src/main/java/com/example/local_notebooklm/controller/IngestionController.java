package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.ingestion.orchestrator.IngestionOrchestrator;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*") // <--- ADD THIS LINE TO FIX CORS!
@RequestMapping("/api/documents")
public class IngestionController {

    private final IngestionOrchestrator ingestionService;

    public IngestionController(IngestionOrchestrator ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Original endpoint — ingests a file by its absolute path on the server machine.
     * POST /api/documents/ingest?filePath=/Users/name/Downloads/Policy.pdf
     */
    @PostMapping("/ingest")
    public String ingestDocument(@RequestParam String filePath) {
        try {
            Path path = Paths.get(filePath);
            ingestionService.ingestFile(path, path.getFileName().toString());
            return "✅ Successfully ingested and vectorized: " + path.getFileName();
        } catch (Exception e) {
            return "❌ Error ingesting file: " + e.getMessage();
        }
    }

    /**
     * New endpoint — accepts an actual file upload from the browser UI.
     * POST /api/documents/upload  (multipart/form-data, field name = "file")
     */
    @PostMapping("/upload")
    public String uploadAndIngest(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            Path tempFile = Files.createTempFile("vault-", "-" + originalName);
            Files.write(tempFile, file.getBytes());

            ingestionService.ingestFile(tempFile, originalName);

            Files.deleteIfExists(tempFile);
            return "✅ Successfully ingested and vectorized: " + originalName;
        } catch (Exception e) {
            return "❌ Error ingesting file: " + e.getMessage();
        }
    }

    /**
     * Batch upload — ingests multiple files in one request.
     * POST /api/documents/upload-batch  (multipart/form-data, field name = "files")
     * Returns a map of filename → "SUCCESS" or "ERROR: <message>"
     */
    @PostMapping("/upload-batch")
    public Map<String, String> uploadBatch(@RequestParam("files") MultipartFile[] files) {
        Map<String, String> results = new LinkedHashMap<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
            try {
                Path tmp = Files.createTempFile("vault-", "-" + name);
                Files.write(tmp, file.getBytes());
                ingestionService.ingestFile(tmp, name);
                Files.deleteIfExists(tmp);
                results.put(name, "SUCCESS");
            } catch (Exception e) {
                results.put(name, "ERROR: " + e.getMessage());
            }
        }
        return results;
    }

    @DeleteMapping("/delete")
    public String deleteDocument(@RequestParam String filename) {
        try {
            ingestionService.deleteFile(filename);
            return "✅ Successfully deleted vectors for: " + filename;
        } catch (Exception e) {
            return "❌ Error deleting file vectors: " + e.getMessage();
        }
    }
}