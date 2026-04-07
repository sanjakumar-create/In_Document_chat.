package com.example.local_notebooklm.ingestion.vectorizer;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.stereotype.Service;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class VectorizationService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public VectorizationService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * Builds the shared ingestor with tuned chunking parameters.
     *
     * 1000 characters (~180–250 words): keeps complete concepts together while staying
     * safely within mxbai-embed-large's 512-token context window.
     * 250 character overlap: preserves context when a concept spans chunk boundaries.
     */
    public EmbeddingStoreIngestor getIngestor() {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 250))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();
    }

    /** Creates a shallow copy of a Metadata object. */
    public Metadata copyMetadata(Metadata src) {
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