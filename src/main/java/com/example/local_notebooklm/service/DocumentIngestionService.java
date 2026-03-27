package com.example.local_notebooklm.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import net.sourceforge.tess4j.Tesseract;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import technology.tabula.ObjectExtractor;
import technology.tabula.Page;
import technology.tabula.RectangularTextContainer;
import technology.tabula.Table;
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentIngestionService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${ocr.tesseract.datapath:/usr/share/tessdata}")
    private String tessdataPath;

    public DocumentIngestionService(EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * Entry point called by IngestionController.
     *
     * Routing:
     *   .pdf  → ingestPdf()     — PDFBox page-by-page + tabula table extraction + Tesseract OCR
     *   .docx → ingestDocx()    — Apache POI with table-aware extraction (prose + Markdown tables)
     *   other → ingestGeneric() — Apache Tika flat-text fallback (XLSX, PPTX, TXT, CSV, ...)
     */
    public void ingestFile(Path filePath, String originalFilename) {
        System.out.println("[INGEST] Starting: " + originalFilename);
        try {
            String lower = originalFilename.toLowerCase();
            if (lower.endsWith(".pdf")) {
                ingestPdf(filePath, originalFilename);
            } else if (lower.endsWith(".docx")) {
                ingestDocx(filePath, originalFilename);
            } else {
                ingestGeneric(filePath, originalFilename);
            }
            System.out.println("[INGEST] Complete: " + originalFilename);
        } catch (Exception e) {
            System.err.println("[INGEST] Error for " + originalFilename + ": " + e.getMessage());
            System.out.println("[INGEST] Falling back to Tika for: " + originalFilename);
            ingestGeneric(filePath, originalFilename);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PDF ingestion — page-by-page with table extraction and OCR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * PDF ingestion pipeline per page:
     * 1. Extract text with PDFBox (setSortByPosition fixes multi-column column order)
     * 2. Detect and skip Table of Contents pages (prevents hallucination from TOC entries)
     * 3. If page has < 60 chars of text, attempt OCR with Tesseract (image-heavy pages)
     * 4. Extract tables using tabula-java, ingest as separate Markdown table chunks
     * 5. Ingest page prose (non-table text) with page metadata
     *
     * Each chunk gets: source_file, page_number, total_pages, content_type metadata.
     * content_type values: "text" | "table" | "ocr"
     */
    private void ingestPdf(Path filePath, String originalFilename) throws IOException {
        EmbeddingStoreIngestor ingestor = buildIngestor();

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
                            Metadata tableMeta = copyMetadata(baseMeta);
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
                    // This prevents one bad page from killing the entire 506-page ingestion.
                    System.out.printf("[INGEST] Page %d failed (%s) — skipping%n",
                                      page, e.getMessage());
                }
            }

            System.out.printf(
                "[INGEST] Pages — text=%d  ocr=%d  tables=%d  skipped_toc=%d  skipped_blank=%d%n",
                ingestedText, ingestedOcr, ingestedTables, skippedToc, skippedBlank);
        }
    }

    /**
     * Extracts tables from a single PDF page using tabula-java.
     * Returns a list of Markdown-formatted table strings (one per detected table).
     *
     * Why tabula? PDFBox extracts all text as a flat stream — table columns land on the
     * same line but with arbitrary spacing, producing garbled text like:
     *   "Product     Price     Qty     Total"
     *   "Widget A    $9.99      50    $499.50"
     * tabula parses the x/y coordinates of each text fragment and reconstructs cell
     * boundaries, returning clean Row × Cell data that we convert to Markdown tables.
     *
     * The Markdown format | col | col | is stored as a chunk so the LLM can reason
     * about structured comparisons directly from the retrieved text.
     */
    /**
     * Extracts tables from a single PDF page using a SHARED ObjectExtractor.
     * The extractor is created once per PDF (not per page) to avoid closing the
     * PDDocument's internal COSStreams prematurely.
     */
    private List<String> extractTablesFromPage(ObjectExtractor extractor, int pageNumber,
                                                String filename) {
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
                System.out.printf("[INGEST] Tables found: %d on page %d of %s%n",
                                  result.size(), pageNumber, filename);
            }
        } catch (Exception e) {
            // tabula fails on scanned/image tables — silent fallback, prose still ingested
        }
        return result;
    }

    /**
     * Converts a tabula Table to a Markdown table string.
     *
     * Example output:
     *   | Name    | Score | Grade |
     *   |---------|-------|-------|
     *   | Alice   | 95    | A     |
     *   | Bob     | 82    | B     |
     *
     * The first row is treated as a header. If the table has no rows, returns null.
     */
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

    /**
     * OCR fallback for image-heavy PDF pages (scanned documents, infographic pages).
     *
     * Process:
     * 1. PDFRenderer renders the page to a BufferedImage at 300 DPI
     * 2. tess4j passes the image to the native Tesseract library
     * 3. Tesseract returns extracted text (English trained data)
     *
     * 300 DPI: optimal for Tesseract on typical A4/Letter document scans.
     * Lower DPI misses small text; higher DPI is slower with diminishing returns.
     *
     * Returns empty string on any failure — the calling loop will skip the page.
     * This is intentional: OCR failure is non-fatal, prose extraction continues.
     */
    private String ocrPdfPage(PDDocument pdf, int pageNumber) {
        try {
            PDFRenderer renderer = new PDFRenderer(pdf);
            // PDFRenderer uses 0-based page index; our loop variable is 1-based
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

    // ─────────────────────────────────────────────────────────────────────────
    // DOCX ingestion — Apache POI table-aware extraction
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * DOCX ingestion preserving table structure.
     *
     * Why not use Tika for DOCX? Tika (and the underlying POI converter) serializes DOCX
     * content as a flat string. Tables become comma-separated text with no column alignment.
     * A pricing table "Product, $9.99, Widget A, $14.99" is meaningless to the RAG model.
     *
     * This method iterates the document body in reading order (paragraphs and tables
     * interleaved) using getBodyElements():
     * - Paragraphs: accumulated into a prose buffer
     * - Tables: prose buffer is flushed as a chunk, then table is ingested as Markdown
     *
     * This preserves the document's natural reading flow while giving tables their own
     * searchable chunks. The model can then retrieve table chunks for comparison questions
     * ("what is the price of X?") and prose chunks for explanatory questions.
     */
    private void ingestDocx(Path filePath, String originalFilename) throws Exception {
        EmbeddingStoreIngestor ingestor = buildIngestor();
        int tableCount = 0;
        int proseChunks = 0;

        try (InputStream is = Files.newInputStream(filePath);
             XWPFDocument doc = new XWPFDocument(is)) {

            StringBuilder proseBuffer = new StringBuilder();
            Metadata baseMeta = new Metadata();
            baseMeta.put("source_file", originalFilename);

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    String text = para.getText().trim();
                    if (!text.isEmpty()) {
                        proseBuffer.append(text).append("\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    // Flush accumulated prose before this table
                    if (proseBuffer.length() > 60) {
                        Metadata m = copyMetadata(baseMeta);
                        m.put("content_type", "text");
                        ingestor.ingest(Document.document(proseBuffer.toString().trim(), m));
                        proseChunks++;
                        proseBuffer.setLength(0);
                    }
                    // Ingest table as Markdown
                    String md = poiTableToMarkdown(table);
                    if (md != null && !md.isBlank()) {
                        Metadata m = copyMetadata(baseMeta);
                        m.put("content_type", "table");
                        ingestor.ingest(Document.document(md, m));
                        tableCount++;
                    }
                }
            }

            // Flush any remaining prose at end of document
            if (proseBuffer.length() > 60) {
                Metadata m = copyMetadata(baseMeta);
                m.put("content_type", "text");
                ingestor.ingest(Document.document(proseBuffer.toString().trim(), m));
                proseChunks++;
            }
        }
        System.out.printf("[INGEST] DOCX — prose chunks=%d  tables=%d  file=%s%n",
                          proseChunks, tableCount, originalFilename);
    }

    /**
     * Converts an Apache POI XWPFTable to a Markdown table string.
     * Iterates rows and cells in document order, building | col | col | rows.
     */
    private String poiTableToMarkdown(XWPFTable table) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        boolean firstRow = true;
        for (XWPFTableRow row : rows) {
            sb.append("| ");
            for (XWPFTableCell cell : row.getTableCells()) {
                String text = cell.getText().trim().replace("\n", " ").replace("|", "\\|");
                sb.append(text).append(" | ");
            }
            sb.append("\n");
            if (firstRow) {
                sb.append("| ");
                for (int i = 0; i < row.getTableCells().size(); i++) sb.append("--- | ");
                sb.append("\n");
                firstRow = false;
            }
        }
        return sb.toString().trim();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Generic Tika ingestion (XLSX, PPTX, TXT, CSV, etc.)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fallback for formats not handled by PDFBox or POI.
     * Apache Tika extracts flat text — no page or table metadata available.
     */
    private void ingestGeneric(Path filePath, String originalFilename) {
        DocumentParser parser  = new ApacheTikaDocumentParser();
        Document document      = FileSystemDocumentLoader.loadDocument(filePath, parser);
        document.metadata().put("source_file", originalFilename);
        document.metadata().put("content_type", "text");
        buildIngestor().ingest(document);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when a PDF page is a Table of Contents.
     *
     * Heuristic: >40% of lines match "text ..... NNN" (trailing dots + page number).
     * This reliably identifies TOC pages without false-positiving on regular text.
     *
     * Example TOC line caught by this pattern:
     *   "Chapter 5 — Regular Expressions .......... 47"
     *
     * Why this matters: without this filter, the model retrieves "Regular Expressions"
     * from the TOC, sees it in context, and synthesises content from training knowledge
     * about regular expressions rather than from the actual document chapter.
     */
    private boolean isTocPage(String pageText) {
        String[] lines = pageText.split("\n");
        if (lines.length < 5) return false;
        long dotLines = Arrays.stream(lines)
            .filter(l -> l.trim().matches(".*[.\\s]{3,}\\d{1,4}\\s*$"))
            .count();
        return (double) dotLines / lines.length > 0.4;
    }

    /**
     * Builds the shared ingestor with tuned chunking parameters.
     *
     * 1000 characters (~180–250 words): keeps complete concepts together while staying
     * safely within mxbai-embed-large's 512-token context window. The previous size of
     * 1500 chars worked for prose (~375 tokens) but code-heavy content (short tokens,
     * many operators) could exceed 512 tokens, causing "input length exceeds context
     * length" errors from Ollama. 1000 chars ≈ 250–350 tokens even for dense code.
     *
     * 250 character overlap: preserves context when a concept spans chunk boundaries.
     */
    private EmbeddingStoreIngestor buildIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 250))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /** Creates a shallow copy of a Metadata object. */
    private Metadata copyMetadata(Metadata src) {
        Metadata copy = new Metadata();
        src.toMap().forEach((k, v) -> copy.put(k, String.valueOf(v)));
        return copy;
    }

    /**
     * Removes all vector chunks for a specific file from ChromaDB.
     * Uses the source_file metadata filter for precise, atomic deletion.
     */
    public void deleteFile(String originalFilename) {
        System.out.println("[DELETE] Removing vectors for: " + originalFilename);
        embeddingStore.removeAll(metadataKey("source_file").isEqualTo(originalFilename));
        System.out.println("[DELETE] Complete: " + originalFilename);
    }
}
