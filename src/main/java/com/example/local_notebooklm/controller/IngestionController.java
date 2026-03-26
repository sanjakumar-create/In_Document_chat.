package com.example.local_notebooklm.controller;

import com.example.local_notebooklm.service.DocumentIngestionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@CrossOrigin(origins = "*") // <--- ADD THIS LINE TO FIX CORS!
@RequestMapping("/api/documents")
public class IngestionController {

    private final DocumentIngestionService ingestionService;

    public IngestionController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * Original endpoint — ingests a file by its absolute path on the server machine.
     * POST /api/documents/ingest?filePath=/Users/name/Downloads/Policy.pdf
     */
    /**
     * Original endpoint — ingests a file by its absolute path on the server machine.
     * POST /api/documents/ingest?filePath=/Users/name/Downloads/Policy.pdf
     */
    @PostMapping("/ingest")
    public String ingestDocument(@RequestParam String filePath) {
        try {
            Path path = Paths.get(filePath);

            // 🛠️ THE FIX: Pass the filename as the second argument here too!
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

            // 2. Pass the clean originalName down to the service!
            ingestionService.ingestFile(tempFile, originalName);

            Files.deleteIfExists(tempFile);
            return "✅ Successfully ingested and vectorized: " + originalName;
        } catch (Exception e) {
            return "❌ Error ingesting file: " + e.getMessage();
        }
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
