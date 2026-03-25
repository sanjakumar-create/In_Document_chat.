package com.example.local_notebooklm.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VectorStoreConfig {

    /**
     * Reads the ChromaDB base URL from application.properties.
     * When running inside Docker the CHROMA_BASE_URL env var overrides this
     * to http://chromadb:8000 (sibling container, internal port).
     * When running locally it defaults to http://localhost:8888.
     */
    @Value("${chroma.base-url:http://localhost:8888}")
    private String chromaBaseUrl;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaBaseUrl)
                .collectionName("my_notebook_docs")
                .build();
    }
}
