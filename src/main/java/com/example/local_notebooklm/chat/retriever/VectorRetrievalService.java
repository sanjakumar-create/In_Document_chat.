package com.example.local_notebooklm.chat.retriever;

import com.example.local_notebooklm.chat.domain.QueryVariant;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class VectorRetrievalService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ExecutorService retrievalPool = Executors.newFixedThreadPool(4);

    public VectorRetrievalService(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    public List<EmbeddingMatch<TextSegment>> retrieveChunksParallel(
            List<QueryVariant> variants, double minScore, int maxResults, List<String> filenames) {

        List<CompletableFuture<List<EmbeddingMatch<TextSegment>>>> futures = variants.stream()
                .map(v -> CompletableFuture.supplyAsync(
                        () -> retrieveChunks(v, minScore, maxResults, filenames),
                        retrievalPool))
                .collect(Collectors.toList());

        Map<String, EmbeddingMatch<TextSegment>> seen = new LinkedHashMap<>();
        futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .forEach(m -> seen.putIfAbsent(m.embedded().text(), m));

        return new ArrayList<>(seen.values());
    }

    private List<EmbeddingMatch<TextSegment>> retrieveChunks(
            QueryVariant variant, double minScore, int maxResults, List<String> filenames) {

        String textToEmbed = variant.isDocumentPassage()
                ? variant.text()
                : "Represent this sentence for searching relevant passages: " + variant.text();

        dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(textToEmbed).content();

        var builder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore);

        if (filenames != null && !filenames.isEmpty()) {
            Filter filter = filenames.size() == 1
                    ? metadataKey("source_file").isEqualTo(filenames.get(0))
                    : metadataKey("source_file").isIn(filenames);
            builder.filter(filter);
        }

        return embeddingStore.search(builder.build()).matches();
    }

    @PreDestroy
    public void shutdown() {
        retrievalPool.shutdown();
    }
}