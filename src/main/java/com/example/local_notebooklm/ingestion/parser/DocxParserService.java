package com.example.local_notebooklm.ingestion.parser;

import com.example.local_notebooklm.ingestion.vectorizer.VectorizationService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class DocxParserService {

    private final VectorizationService vectorizer;

    public DocxParserService(VectorizationService vectorizer) {
        this.vectorizer = vectorizer;
    }

    public void parse(Path filePath, String originalFilename) throws Exception {
        EmbeddingStoreIngestor ingestor = vectorizer.getIngestor();
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
                        Metadata m = vectorizer.copyMetadata(baseMeta);
                        m.put("content_type", "text");
                        ingestor.ingest(Document.document(proseBuffer.toString().trim(), m));
                        proseChunks++;
                        proseBuffer.setLength(0);
                    }
                    // Ingest table as Markdown
                    String md = poiTableToMarkdown(table);
                    if (md != null && !md.isBlank()) {
                        Metadata m = vectorizer.copyMetadata(baseMeta);
                        m.put("content_type", "table");
                        ingestor.ingest(Document.document(md, m));
                        tableCount++;
                    }
                }
            }

            // Flush any remaining prose at end of document
            if (proseBuffer.length() > 60) {
                Metadata m = vectorizer.copyMetadata(baseMeta);
                m.put("content_type", "text");
                ingestor.ingest(Document.document(proseBuffer.toString().trim(), m));
                proseChunks++;
            }
        }
        System.out.printf("[INGEST] DOCX — prose chunks=%d  tables=%d  file=%s%n",
                proseChunks, tableCount, originalFilename);
    }

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
}