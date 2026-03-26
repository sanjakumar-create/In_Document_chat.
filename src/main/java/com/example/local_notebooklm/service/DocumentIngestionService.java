
package com.example.local_notebooklm.service;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
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

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

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
// 1. Add "String originalFilename" to the parameters
    public void ingestFile(Path filePath, String originalFilename) {
        System.out.println("Starting ingestion for: " + originalFilename);

        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = FileSystemDocumentLoader.loadDocument(filePath, parser);

        // 🔒 THE MAGIC FIX: Stamp it with the CLEAN name, not the temp file name!
        document.metadata().put("source_file", originalFilename);

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 200))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(document);
        System.out.println("Successfully vectorized and saved: " + originalFilename);
    }
    /**
     * Deletes all vector chunks associated with a specific file name from ChromaDB.
     */
    public void deleteFile(String originalFilename) {
        System.out.println("🗑️ Attempting to delete vectors for: " + originalFilename);

        // Tells ChromaDB to delete everything where "source_file" matches the filename
        embeddingStore.removeAll(metadataKey("source_file").isEqualTo(originalFilename));

        System.out.println("✅ Successfully deleted all vectors for: " + originalFilename);
    }
}