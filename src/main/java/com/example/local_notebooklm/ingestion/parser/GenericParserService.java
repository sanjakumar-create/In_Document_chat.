package com.example.local_notebooklm.ingestion.parser;

import com.example.local_notebooklm.ingestion.vectorizer.VectorizationService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class GenericParserService {

    private final VectorizationService vectorizer;

    public GenericParserService(VectorizationService vectorizer) {
        this.vectorizer = vectorizer;
    }

    public void parse(Path filePath, String originalFilename) {
        DocumentParser parser  = new ApacheTikaDocumentParser();
        Document document      = FileSystemDocumentLoader.loadDocument(filePath, parser);
        document.metadata().put("source_file", originalFilename);
        document.metadata().put("content_type", "text");
        vectorizer.getIngestor().ingest(document);
    }
}