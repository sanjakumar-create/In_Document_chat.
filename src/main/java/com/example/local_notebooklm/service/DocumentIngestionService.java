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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class DocumentIngestionService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public DocumentIngestionService(EmbeddingModel embeddingModel,
                                    EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * Entry point called by IngestionController.
     * Routes PDFs to page-aware PDFBox extraction; all other formats go through Tika.
     */
    public void ingestFile(Path filePath, String originalFilename) {
        System.out.println("[INGEST] Starting: " + originalFilename);
        try {
            if (originalFilename.toLowerCase().endsWith(".pdf")) {
                ingestPdf(filePath, originalFilename);
            } else {
                ingestGeneric(filePath, originalFilename);
            }
            System.out.println("[INGEST] Complete: " + originalFilename);
        } catch (Exception e) {
            System.err.println("[INGEST] Error for " + originalFilename + ": " + e.getMessage());
            // Fall back to Tika on any PDFBox error
            System.out.println("[INGEST] Falling back to Tika for: " + originalFilename);
            ingestGeneric(filePath, originalFilename);
        }
    }

    /**
     * PDF ingestion: extract text page-by-page using PDFBox.
     *
     * Why page-by-page?
     *   - Each chunk is stamped with page_number metadata so citations can show
     *     "Page 12" instead of an anonymous text blob.
     *   - setSortByPosition(true) reads multi-column PDFs in correct left→right order.
     *   - TOC pages are detected and skipped to prevent hallucination from chapter title
     *     entries being used as if they were actual content.
     */
    private void ingestPdf(Path filePath, String originalFilename) throws IOException {
        EmbeddingStoreIngestor ingestor = buildIngestor();

        try (PDDocument pdf = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // Correct column order for multi-column PDFs

            int totalPages = pdf.getNumberOfPages();
            System.out.println("[INGEST] PDF pages: " + totalPages);

            int skippedToc  = 0;
            int skippedBlank = 0;
            int ingested    = 0;

            for (int page = 1; page <= totalPages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdf).trim();

                // Skip near-empty pages (images-only, blank pages)
                if (pageText.length() < 60) { skippedBlank++; continue; }

                // Skip Table of Contents pages — they cause hallucination
                // (a TOC entry "Regular Expressions .... 47" makes the model think
                //  it "knows" the content of that chapter)
                if (isTocPage(pageText)) { skippedToc++; continue; }

                Metadata meta = new Metadata();
                meta.put("source_file",  originalFilename);
                meta.put("page_number",  String.valueOf(page));
                meta.put("total_pages",  String.valueOf(totalPages));

                Document pageDoc = Document.document(pageText, meta);
                ingestor.ingest(pageDoc);
                ingested++;
            }
            System.out.printf("[INGEST] Pages — ingested=%d  skipped_toc=%d  skipped_blank=%d%n",
                              ingested, skippedToc, skippedBlank);
        }
    }

    /**
     * Non-PDF ingestion via Apache Tika (DOCX, XLSX, PPTX, TXT, CSV, …).
     * No page metadata is available — source_file is the only metadata.
     */
    private void ingestGeneric(Path filePath, String originalFilename) {
        DocumentParser parser   = new ApacheTikaDocumentParser();
        Document document       = FileSystemDocumentLoader.loadDocument(filePath, parser);
        document.metadata().put("source_file", originalFilename);
        buildIngestor().ingest(document);
    }

    /**
     * Returns true when a page looks like a Table of Contents.
     *
     * Detection heuristic: if more than 40 % of lines match the pattern
     * "text ..... NNN" (dots or spaces followed by a page number), it is
     * almost certainly a TOC page.
     *
     * Example TOC line that triggers this:
     *   "Chapter 5 — Regular Expressions .......... 47"
     * Without this filter that line would be retrieved by CRAG and the model
     * would synthesise regex content from its training knowledge rather than
     * from the actual chapter text.
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
     * 1500 characters (~280–320 words) keeps complete concepts together.
     * 400 character overlap ensures context is not lost when a concept spans
     * two adjacent chunks (e.g. a definition that starts at the end of one
     * chunk and the explanation is at the start of the next).
     */
    private EmbeddingStoreIngestor buildIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1500, 400))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /**
     * Removes all vector chunks for a specific file from ChromaDB.
     * Uses the source_file metadata filter for precise deletion.
     */
    public void deleteFile(String originalFilename) {
        System.out.println("[DELETE] Removing vectors for: " + originalFilename);
        embeddingStore.removeAll(metadataKey("source_file").isEqualTo(originalFilename));
        System.out.println("[DELETE] Complete: " + originalFilename);
    }
}
