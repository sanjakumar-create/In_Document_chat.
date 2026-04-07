package com.example.local_notebooklm.ingestion.parser;

import com.example.local_notebooklm.ingestion.vectorizer.VectorizationService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class PdfParserService {

    private final VectorizationService vectorizer;

    @Value("${ocr.tesseract.datapath:/usr/share/tessdata}")
    private String tessdataPath;

    public PdfParserService(VectorizationService vectorizer) {
        this.vectorizer = vectorizer;
    }

    public void parse(Path filePath, String originalFilename) throws IOException {
        EmbeddingStoreIngestor ingestor = vectorizer.getIngestor();

        try (PDDocument pdf = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // reads multi-column PDFs left→right correctly

            int totalPages  = pdf.getNumberOfPages();
            int skippedToc  = 0;
            int skippedBlank = 0;
            int ingestedText = 0;
            int ingestedOcr  = 0;
            int ingestedTables = 0;

            System.out.println("[INGEST] PDF pages: " + totalPages);

            // ── Create ONE ObjectExtractor for all pages ──────────────────────
            // BUG FIX: creating a new ObjectExtractor per page (via try-with-resources)
            // was closing the PDDocument's internal COSStreams after page 1. All subsequent
            // pages would fail with "COSStream has been closed" — crashing the entire
            // PDF ingestion and falling back to Tika (which also fails on large PDFs).
            ObjectExtractor tableExtractor = null;
            try {
                tableExtractor = new ObjectExtractor(pdf);
            } catch (Exception e) {
                System.out.println("[INGEST] tabula init failed (tables will be skipped): " + e.getMessage());
            }

            for (int page = 1; page <= totalPages; page++) {
                try {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String pageText = stripper.getText(pdf).trim();

                    if (isTocPage(pageText)) { skippedToc++; continue; }

                    Metadata baseMeta = new Metadata();
                    baseMeta.put("source_file", originalFilename);
                    baseMeta.put("page_number",  String.valueOf(page));
                    baseMeta.put("total_pages",  String.valueOf(totalPages));

                    // === Table extraction (reusing single ObjectExtractor) ===
                    if (tableExtractor != null) {
                        List<String> tables = extractTablesFromPage(tableExtractor, page, originalFilename);
                        for (String md : tables) {
                            Metadata tableMeta = vectorizer.copyMetadata(baseMeta);
                            tableMeta.put("content_type", "table");
                            ingestor.ingest(Document.document(md, tableMeta));
                            ingestedTables++;
                        }
                    }

                    // === Prose text (or OCR fallback) ===
                    String contentType;
                    if (pageText.length() < 60) {
                        pageText = ocrPdfPage(pdf, page);
                        if (pageText.length() < 60) { skippedBlank++; continue; }
                        contentType = "ocr";
                        ingestedOcr++;
                    } else {
                        contentType = "text";
                        ingestedText++;
                    }

                    baseMeta.put("content_type", contentType);
                    ingestor.ingest(Document.document(pageText, baseMeta));
                } catch (Exception e) {
                    // Per-page error is non-fatal — log and continue to next page.
                    System.out.printf("[INGEST] Page %d failed (%s) — skipping%n", page, e.getMessage());
                }
            }

            System.out.printf(
                    "[INGEST] Pages — text=%d  ocr=%d  tables=%d  skipped_toc=%d  skipped_blank=%d%n",
                    ingestedText, ingestedOcr, ingestedTables, skippedToc, skippedBlank);
        }
    }

    private List<String> extractTablesFromPage(ObjectExtractor extractor, int pageNumber, String filename) {
        List<String> result = new ArrayList<>();
        try {
            Page tabulaPage = extractor.extract(pageNumber);
            SpreadsheetExtractionAlgorithm sea = new SpreadsheetExtractionAlgorithm();
            List<Table> tables = sea.extract(tabulaPage);

            for (Table table : tables) {
                String md = tabulaTableToMarkdown(table);
                if (md != null && !md.isBlank()) {
                    result.add(md);
                }
            }
            if (!result.isEmpty()) {
                System.out.printf("[INGEST] Tables found: %d on page %d of %s%n", result.size(), pageNumber, filename);
            }
        } catch (Exception e) {
            // tabula fails on scanned/image tables — silent fallback, prose still ingested
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String tabulaTableToMarkdown(Table table) {
        // tabula-java returns raw List<List<RectangularTextContainer>> — suppressed intentionally
        List<List<RectangularTextContainer<?>>> rows =
                (List<List<RectangularTextContainer<?>>>) (List<?>) table.getRows();
        if (rows == null || rows.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        boolean firstRow = true;
        for (List<RectangularTextContainer<?>> row : rows) {
            sb.append("| ");
            for (RectangularTextContainer<?> cell : row) {
                String text = cell.getText().trim().replace("\n", " ").replace("|", "\\|");
                sb.append(text).append(" | ");
            }
            sb.append("\n");
            if (firstRow) {
                // Add separator row after the header
                sb.append("| ");
                for (int i = 0; i < row.size(); i++) sb.append("--- | ");
                sb.append("\n");
                firstRow = false;
            }
        }
        return sb.toString().trim();
    }

    private String ocrPdfPage(PDDocument pdf, int pageNumber) {
        try {
            PDFRenderer renderer = new PDFRenderer(pdf);
            BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, 300, ImageType.RGB);

            Tesseract tesseract = new Tesseract();
            tesseract.setDatapath(tessdataPath);
            tesseract.setLanguage("eng");

            String text = tesseract.doOCR(image).trim();
            System.out.printf("[OCR] Page %d: extracted %d chars%n", pageNumber, text.length());
            return text;
        } catch (Exception e) {
            System.out.printf("[OCR] Page %d: failed (%s) — skipping%n", pageNumber, e.getMessage());
            return "";
        }
    }

    private boolean isTocPage(String pageText) {
        String[] lines = pageText.split("\n");
        if (lines.length < 5) return false;
        long dotLines = Arrays.stream(lines)
                .filter(l -> l.trim().matches(".*[.\\s]{3,}\\d{1,4}\\s*$"))
                .count();
        return (double) dotLines / lines.length > 0.4;
    }
}