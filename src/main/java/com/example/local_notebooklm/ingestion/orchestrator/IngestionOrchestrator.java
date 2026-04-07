package com.example.local_notebooklm.ingestion.orchestrator;

import com.example.local_notebooklm.ingestion.parser.DocxParserService;
import com.example.local_notebooklm.ingestion.parser.GenericParserService;
import com.example.local_notebooklm.ingestion.parser.PdfParserService;
import com.example.local_notebooklm.ingestion.vectorizer.VectorizationService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class IngestionOrchestrator {

    private final PdfParserService pdfParser;
    private final DocxParserService docxParser;
    private final GenericParserService genericParser;
    private final VectorizationService vectorizer;

    public IngestionOrchestrator(PdfParserService pdfParser, DocxParserService docxParser,
                                 GenericParserService genericParser, VectorizationService vectorizer) {
        this.pdfParser = pdfParser;
        this.docxParser = docxParser;
        this.genericParser = genericParser;
        this.vectorizer = vectorizer;
    }

    public void ingestFile(Path filePath, String originalFilename) {
        System.out.println("[INGEST] Starting: " + originalFilename);
        try {
            String lower = originalFilename.toLowerCase();
            if (lower.endsWith(".pdf")) {
                pdfParser.parse(filePath, originalFilename);
            } else if (lower.endsWith(".docx")) {
                docxParser.parse(filePath, originalFilename);
            } else {
                genericParser.parse(filePath, originalFilename);
            }
            System.out.println("[INGEST] Complete: " + originalFilename);
        } catch (Exception e) {
            System.err.println("[INGEST] Error for " + originalFilename + ": " + e.getMessage());
            System.out.println("[INGEST] Falling back to Tika for: " + originalFilename);
            genericParser.parse(filePath, originalFilename);
        }
    }

    public void deleteFile(String originalFilename) {
        vectorizer.deleteFile(originalFilename);
    }
}