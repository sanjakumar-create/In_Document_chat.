package com.example.local_notebooklm.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class DocumentIngestionService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // Spring Boot automatically injects the Ollama model and your ChromaDB config here
    public DocumentIngestionService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * Reads a file from your computer, chunks it, and saves it to the vector database.
     * @param filePath The absolute path to the file (e.g., "C:/docs/my_notes.pdf" or "/Users/name/docs/my_notes.pdf")
     */
    public void ingestFile(Path filePath) {
        System.out.println("Starting ingestion for: " + filePath.getFileName());

        // 1. Initialize Apache Tika to parse the file (works for PDF, DOCX, TXT, etc.)
        DocumentParser parser = new ApacheTikaDocumentParser();

        // 2. Load the document from your local file system
        Document document = FileSystemDocumentLoader.loadDocument(filePath, parser);

        // 3. Build the Ingestor Pipeline
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                // Split the text into chunks of 500 characters, with a 50-character overlap
                .documentSplitter(DocumentSplitters.recursive(300, 60))
                // Tell it to use Ollama to create the vector embeddings
                .embeddingModel(embeddingModel)
                // Tell it to save those embeddings into ChromaDB
                .embeddingStore(embeddingStore)
                .build();

        // 4. Run the pipeline!
        ingestor.ingest(document);

        System.out.println("Successfully vectorized and saved: " + filePath.getFileName());
    }
}