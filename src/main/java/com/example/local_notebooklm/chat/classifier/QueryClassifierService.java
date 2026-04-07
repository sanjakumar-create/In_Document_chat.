package com.example.local_notebooklm.chat.classifier;

import com.example.local_notebooklm.chat.domain.QueryType;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class QueryClassifierService {

    private final EmbeddingModel embeddingModel;

    private static final List<String> SYNTHESIS_PROTOTYPES = List.of(
            "summarize this document", "give me an overview of this", "what are the key points in this",
            "tell me what this document is about", "what are the main topics covered here",
            "give me a summary of this doc", "explain what this document contains",
            "can you sum up this document", "what is covered in this document", "describe the contents of this"
    );
    private static final List<String> CODE_SEARCH_PROTOTYPES = List.of(
            "show me a code example for this", "how to implement this in code", "what is the syntax for this",
            "give me an example of how to use this", "write code that demonstrates this concept",
            "what does this function do", "how do I code this in python"
    );
    private static final List<String> FACTOID_PROTOTYPES = List.of(
            "how does this work in detail", "what is the difference between these two things",
            "explain the process step by step", "what are the requirements for this feature",
            "why does this behavior happen", "what is the detailed explanation with examples",
            "describe how this mechanism operates", "what happens when this condition occurs"
    );

    private volatile float[] synthesisCentroid = null;
    private volatile float[] codeSearchCentroid = null;
    private volatile float[] factoidCentroid = null;
    private final Object centroidLock = new Object();

    public QueryClassifierService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public QueryType classifyQuery(String question) {
        ensurePrototypesLoaded();
        if (synthesisCentroid == null) {
            return classifyQueryRegex(question);
        }
        try {
            float[] qVec = embeddingModel.embed(question).content().vector();

            float simSynthesis = cosineSimilarity(qVec, synthesisCentroid);
            float simCode = cosineSimilarity(qVec, codeSearchCentroid);
            float simFactoid = cosineSimilarity(qVec, factoidCentroid);

            System.out.printf("[CLASSIFIER] Sim → SYNTHESIS:%.3f  CODE:%.3f  FACTOID:%.3f%n",
                    simSynthesis, simCode, simFactoid);

            if (simSynthesis >= simCode && simSynthesis >= simFactoid) return QueryType.SYNTHESIS;
            if (simCode >= simFactoid) return QueryType.CODE_SEARCH;
            return QueryType.FACTOID;

        } catch (Exception e) {
            System.out.println("[CLASSIFIER] Embedding failed, using regex fallback: " + e.getMessage());
            return classifyQueryRegex(question);
        }
    }

    private void ensurePrototypesLoaded() {
        if (synthesisCentroid != null) return;
        synchronized (centroidLock) {
            if (synthesisCentroid != null) return;
            try {
                System.out.println("[CLASSIFIER] Computing prototype centroids from mxbai-embed-large...");
                synthesisCentroid = computeCentroid(SYNTHESIS_PROTOTYPES);
                codeSearchCentroid = computeCentroid(CODE_SEARCH_PROTOTYPES);
                factoidCentroid = computeCentroid(FACTOID_PROTOTYPES);
                System.out.printf("[CLASSIFIER] Ready. Prototype dim=%d, classes=3%n", synthesisCentroid.length);
            } catch (Exception e) {
                System.out.println("[CLASSIFIER] Prototype load FAILED — will use regex: " + e.getMessage());
            }
        }
    }

    private float[] computeCentroid(List<String> prototypes) {
        List<float[]> vectors = new ArrayList<>();
        for (String proto : prototypes) {
            vectors.add(embeddingModel.embed(proto).content().vector());
        }
        int dim = vectors.get(0).length;
        float[] centroid = new float[dim];
        for (float[] v : vectors) {
            for (int i = 0; i < dim; i++) centroid[i] += v[i];
        }
        for (int i = 0; i < dim; i++) centroid[i] /= vectors.size();
        return centroid;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    private QueryType classifyQueryRegex(String question) {
        String q = question.toLowerCase().trim();
        if (q.matches(".*(summarize|summarise|overview|outline|explain the|describe the|" +
                "tell me about|what does (this|the) document|what is (this|the) document|" +
                "key (points|topics)|main (points|topics)|topics covered).*")) {
            return QueryType.SYNTHESIS;
        }
        if (q.split("\\s+").length <= 5) {
            return QueryType.CODE_SEARCH;
        }
        return QueryType.FACTOID;
    }
}