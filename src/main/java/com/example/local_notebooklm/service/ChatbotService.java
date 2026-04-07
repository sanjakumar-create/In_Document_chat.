package com.example.local_notebooklm.service;

import com.example.local_notebooklm.dto.ChatResponse;
import com.example.local_notebooklm.inference.distributed.router.InferenceServiceAdapter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

//@Service
public class ChatbotService {

    // Classifies the user's query so the pipeline can adapt thresholds and HyDE strategy.
    // SYNTHESIS — summarize, overview, key points: skip HyDE, lower CRAG threshold to 3.
    // CODE_SEARCH — short/concept queries on code docs: use code-focused HyDE, lower CRAG.
    // FACTOID — everything else: standard prose HyDE, CRAG threshold 4 (unchanged from v3).
    private enum QueryType { SYNTHESIS, CODE_SEARCH, FACTOID }

    /**
     * Carries a query variant alongside a flag indicating how it should be embedded.
     *
     * isDocumentPassage = true  → embed WITHOUT instruction prefix (passage space).
     *   Used for HyDE-generated passages. mxbai-embed-large stores document chunks with
     *   no prefix (passage space). A HyDE passage is a fake document — it should sit in
     *   the SAME space as real chunks so similarity is meaningful.
     *
     * isDocumentPassage = false → embed WITH instruction prefix (query space).
     *   Used for raw questions, multi-query paraphrases, and synthesis anchor strings.
     *   mxbai-embed-large is trained to match query-space vectors to passage-space vectors.
     */
    private record QueryVariant(String text, boolean isDocumentPassage) {}

    private final ChatLanguageModel llm;
    private final InferenceServiceAdapter inferenceAdapter;
    private final EmbeddingModel    embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    @Value("${rag.query-expansion.mode:hyde}")
    private String queryExpansionMode;

    @Value("${rag.crag.batch.min-score:3}")
    private int cragMinScore;

    @Value("${rag.crag.batch.min-score.synthesis:3}")
    private int cragMinScoreSynthesis;

    @Value("${rag.crag.batch.min-score.code:3}")
    private int cragMinScoreCode;

    @Value("${rag.retrieval.min-score.relax-for-synthesis:0.20}")
    private double relaxMinScoreSynthesis;

    @Value("${rag.retrieval.min-score.relax-for-code:0.20}")
    private double relaxMinScoreCode;

    @Value("${rag.retrieval.min-score.relax-for-factoid:0.20}")
    private double relaxMinScoreFactoid;

    // ── Embedding-based query classifier ─────────────────────────────────────
    // Prototype sentences define each intent class. Centroids are computed ONCE
    // at first query by embedding each prototype with mxbai-embed-large (the model
    // already running for retrieval — zero extra hardware cost). Subsequent calls
    // use the cached centroids for O(dim) cosine similarity — microseconds.
    private static final List<String> SYNTHESIS_PROTOTYPES = List.of(
        "summarize this document",
        "give me an overview of this",
        "what are the key points in this",
        "tell me what this document is about",
        "what are the main topics covered here",
        "give me a summary of this doc",
        "explain what this document contains",
        "can you sum up this document",
        "what is covered in this document",
        "describe the contents of this"
    );
    private static final List<String> CODE_SEARCH_PROTOTYPES = List.of(
        "show me a code example for this",
        "how to implement this in code",
        "what is the syntax for this",
        "give me an example of how to use this",
        "write code that demonstrates this concept",
        "what does this function do",
        "how do I code this in python"
    );
    private static final List<String> FACTOID_PROTOTYPES = List.of(
        "how does this work in detail",
        "what is the difference between these two things",
        "explain the process step by step",
        "what are the requirements for this feature",
        "why does this behavior happen",
        "what is the detailed explanation with examples",
        "describe how this mechanism operates",
        "what happens when this condition occurs"
    );
    // Cached centroids (loaded lazily on first query)
    private volatile float[] synthesisCentroid  = null;
    private volatile float[] codeSearchCentroid = null;
    private volatile float[] factoidCentroid    = null;
    private final Object centroidLock = new Object();

    // ── Per-session isolated memory ───────────────────────────────────────────
    // Each browser tab gets its own 20-message rolling window keyed by UUID sessionId.
    // Sessions idle > 30 minutes are automatically evicted by a background scheduler.
    private static class SessionEntry {
        final ChatMemory memory      = MessageWindowChatMemory.withMaxMessages(20);
        volatile long    lastAccessed = System.currentTimeMillis();
    }
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler;

    // Bounded thread pool for parallel embedding calls (one per query variant).
    // Ollama processes one request at a time, so 4 threads queue requests without flooding.
    private final ExecutorService retrievalPool = Executors.newFixedThreadPool(4);

    public ChatbotService(ChatLanguageModel chatLanguageModel,
                          InferenceServiceAdapter inferenceAdapter,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.llm            = chatLanguageModel;
        this.inferenceAdapter = inferenceAdapter;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;

        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor();
        evictionScheduler.scheduleAtFixedRate(this::evictExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        retrievalPool.shutdown();
        evictionScheduler.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session management
    // ─────────────────────────────────────────────────────────────────────────

    private ChatMemory getSession(String sessionId) {
        SessionEntry entry = sessions.compute(sessionId, (id, existing) -> {
            if (existing != null) {
                existing.lastAccessed = System.currentTimeMillis();
                return existing;
            }
            return new SessionEntry();
        });
        return entry.memory;
    }

    private void evictExpiredSessions() {
        long cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30);
        int before  = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().lastAccessed < cutoff);
        System.out.printf("[Sessions] Evicted %d idle sessions. Active: %d%n",
                          before - sessions.size(), sessions.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step A: Query expansion — HyDE | multi-query | none
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classifies the query into SYNTHESIS, CODE_SEARCH, or FACTOID using
     * embedding-based prototype matching. mxbai-embed-large is already loaded in
     * Ollama for retrieval, so this adds zero hardware cost.
     *
     * On first call: embeds SYNTHESIS/CODE_SEARCH/FACTOID prototype sentences and
     * averages them into per-class centroids (cached for the lifetime of the app).
     * On every call: embeds the incoming question (~50ms), computes cosine similarity
     * to each centroid, picks the highest.
     *
     * Advantages over regex:
     *   - Handles typos: "summerize" scores high similarity to SYNTHESIS centroid
     *   - Handles synonyms: "tldr", "recap", "sum it up" all match SYNTHESIS
     *   - Language-agnostic: works in Hindi, Spanish, etc. without new rules
     *
     * Falls back to regex classifier if Ollama is unavailable at classification time.
     */
    private QueryType classifyQuery(String question) {
        ensurePrototypesLoaded();
        if (synthesisCentroid == null) {
            // Prototype loading failed (Ollama not ready) — use regex fallback
            return classifyQueryRegex(question);
        }
        try {
            // Embed question in passage space (no instruction prefix) for symmetric comparison
            float[] qVec = embeddingModel.embed(question).content().vector();

            float simSynthesis  = cosineSimilarity(qVec, synthesisCentroid);
            float simCode       = cosineSimilarity(qVec, codeSearchCentroid);
            float simFactoid    = cosineSimilarity(qVec, factoidCentroid);

            System.out.printf("[CLASSIFIER] Sim → SYNTHESIS:%.3f  CODE:%.3f  FACTOID:%.3f%n",
                              simSynthesis, simCode, simFactoid);

            if (simSynthesis >= simCode && simSynthesis >= simFactoid) return QueryType.SYNTHESIS;
            if (simCode      >= simFactoid)                             return QueryType.CODE_SEARCH;
            return QueryType.FACTOID;

        } catch (Exception e) {
            System.out.println("[CLASSIFIER] Embedding failed, using regex fallback: " + e.getMessage());
            return classifyQueryRegex(question);
        }
    }

    /**
     * Loads prototype embeddings for all three query classes and averages them
     * into per-class centroid vectors. Runs at most once (lazy, thread-safe via DCL).
     * Uses mxbai-embed-large with NO instruction prefix — symmetric embedding space
     * is appropriate for query-to-query comparison (vs. query-to-document retrieval).
     */
    private void ensurePrototypesLoaded() {
        if (synthesisCentroid != null) return;  // fast path — already loaded
        synchronized (centroidLock) {
            if (synthesisCentroid != null) return;  // double-checked locking
            try {
                System.out.println("[CLASSIFIER] Computing prototype centroids from mxbai-embed-large...");
                synthesisCentroid  = computeCentroid(SYNTHESIS_PROTOTYPES);
                codeSearchCentroid = computeCentroid(CODE_SEARCH_PROTOTYPES);
                factoidCentroid    = computeCentroid(FACTOID_PROTOTYPES);
                System.out.printf("[CLASSIFIER] Ready. Prototype dim=%d, classes=3%n",
                                  synthesisCentroid.length);
            } catch (Exception e) {
                System.out.println("[CLASSIFIER] Prototype load FAILED — will use regex: " + e.getMessage());
                // Leave centroids null so caller falls back to regex
            }
        }
    }

    /** Embeds each prototype sentence and returns their element-wise average (centroid). */
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

    /** Standard cosine similarity between two vectors. Returns 0 for zero vectors. */
    private float cosineSimilarity(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    /**
     * Regex-based fallback classifier. Used when the embedding model is unavailable
     * (e.g., Ollama not ready during prototype loading) or on embedding exceptions.
     * Kept as a safety net — the embedding classifier handles everything in normal operation.
     */
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

    /**
     * Dispatches to the configured query expansion strategy, adapted per query type.
     *
     * HyDE (Hypothetical Document Embedding): asks Gemma 2 to write a short passage
     * that would appear in a document and directly answer the question. That passage is
     * then embedded instead of the raw question. Because mxbai-embed-large is asymmetric
     * (query encoder ≠ passage encoder), embedding a fake document passage produces a
     * vector much closer to real passage vectors than embedding the question directly.
     * This dramatically improves retrieval recall.
     *
     * Reference: Gao et al. 2022 — "Precise Zero-Shot Dense Retrieval without Relevance Labels"
     * (https://arxiv.org/abs/2212.10496)
     *
     * Adaptations by query type (hyde mode only):
     *   SYNTHESIS   — bypass HyDE (no LLM call); use raw question + two broad anchor strings
     *                 so retrieval spreads across the whole document. 2 LLM calls total.
     *   CODE_SEARCH — generate a code-snippet HyDE (stays in code embedding space) + raw question
     *                 as safety fallback. Still 1 LLM call.
     *   FACTOID     — original prose HyDE (unchanged). 1 LLM call.
     */
    private List<QueryVariant> queryVariants(String question, QueryType type) {
        return switch (queryExpansionMode) {
            case "hyde" -> switch (type) {
                // SYNTHESIS: skip HyDE entirely. Raw question + broad anchors spread retrieval
                // across the whole document. All embedded as queries (query space).
                case SYNTHESIS -> List.of(
                    new QueryVariant(question, false),
                    new QueryVariant("contents topics summary overview", false),
                    new QueryVariant("main points key concepts introduction", false));
                // CODE_SEARCH: code-snippet HyDE embedded as a DOCUMENT (passage space, no prefix)
                // so it sits next to real code chunks. Raw question included as query-space fallback.
                case CODE_SEARCH -> List.of(
                    new QueryVariant(generateHypotheticalCodePassage(question), true),
                    new QueryVariant(question, false));
                // FACTOID: prose HyDE embedded as a DOCUMENT (passage space, no prefix).
                // Fixes the original bug where HyDE passages got the query prefix,
                // pushing them into query space far from the stored passage-space chunks.
                default -> List.of(new QueryVariant(generateHypotheticalPassage(question), true));
            };
            case "multi-query" -> expandQuery(question).stream()
                .map(q -> new QueryVariant(q, false))
                .collect(Collectors.toList());
            default -> List.of(new QueryVariant(question, false));  // "none"
        };
    }

    private String generateHypotheticalPassage(String question) {
        String prompt =
            "Write a short factual paragraph (3–5 sentences) that would appear in a document " +
            "and directly answers the following question. Use domain-appropriate language. " +
            "Write as if extracted from a document — not as a reply to the user.\n\n" +
            "Question: " + question + "\n\nPassage:";
        try {
            return resolveChatModel().generate(prompt).trim();
        } catch (Exception e) {
            System.out.println("[QUERY] HyDE failed, using raw question: " + e.getMessage());
            return question;
        }
    }

    /**
     * Code-focused HyDE for CODE_SEARCH queries.
     * Generates a short code snippet with an inline comment. Because the output is code,
     * its embedding lands in the code vector space — much closer to real code chunks than
     * a prose HyDE passage would be. Falls back to raw question on failure.
     */
    private String generateHypotheticalCodePassage(String question) {
        String prompt =
            "Write a SHORT code example (3–8 lines) with a one-line comment that would appear " +
            "in a programming tutorial document and directly demonstrates: " + question + "\n" +
            "Write ONLY code and inline comments. No prose explanation.\n\nCode:";
        try {
            return resolveChatModel().generate(prompt).trim();
        } catch (Exception e) {
            System.out.println("[QUERY] Code HyDE failed, using raw question: " + e.getMessage());
            return question;
        }
    }

    private List<String> expandQuery(String question) {
        String prompt =
            "Generate exactly 3 different phrasings of this question for document search.\n" +
            "Use different vocabulary but ask for the same information.\n" +
            "Output exactly 3 lines. No numbering, no bullets, no explanations.\n\n" +
            "Question: " + question;
        try {
            String[] lines = resolveChatModel().generate(prompt).trim().split("\n");
            List<String> variants = Arrays.stream(lines)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() > 5)
                .limit(3)
                .collect(Collectors.toList());
            variants.add(question);
            System.out.println("[QUERY] Expanded to " + variants.size() + " variants");
            return variants;
        } catch (Exception e) {
            System.out.println("[QUERY] Multi-query failed, using original: " + e.getMessage());
            return List.of(question);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step B: Parallel retrieval with multi-file filter support
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves chunks for multiple query variants in parallel using a bounded thread pool.
     * Results are deduplicated by chunk text using LinkedHashMap (preserving first-seen order).
     *
     * filenames == null → no filter, searches ALL ingested documents
     * filenames.size() == 1 → isEqualTo filter (original single-file behavior)
     * filenames.size() > 1 → isIn filter (ChromaDB $in operator, confirmed in 0.4.24)
     */
    private List<EmbeddingMatch<TextSegment>> retrieveChunksParallel(
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

    /**
     * Single-variant retrieval. Applies the mxbai-embed-large instruction prefix only for
     * query-space variants (raw questions, multi-query paraphrases, synthesis anchors).
     * HyDE passages (isDocumentPassage=true) are embedded WITHOUT the prefix so they land
     * in passage space — the same space as the stored document chunks. This is the correct
     * way to use HyDE with an asymmetric embedding model: the fake document should be
     * comparable to real documents, not to queries.
     */
    private List<EmbeddingMatch<TextSegment>> retrieveChunks(
            QueryVariant variant, double minScore, int maxResults, List<String> filenames) {

        String textToEmbed = variant.isDocumentPassage()
            ? variant.text()
            : "Represent this sentence for searching relevant passages: " + variant.text();
        dev.langchain4j.data.embedding.Embedding queryEmbedding =
            embeddingModel.embed(textToEmbed).content();

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
        // null filenames → no filter → queries entire ChromaDB collection

        return embeddingStore.search(builder.build()).matches();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step C: Batch CRAG evaluation (replaces 2-gate per-chunk + rerankChunks)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Evaluates ALL chunks in a single LLM call, returning relevance scores 1–5.
     *
     * Why batch instead of per-chunk?
     *   v2 used 2 LLM calls per chunk: for 10 chunks = 20 calls + 10 re-rank calls = 30 total.
     *   Batch CRAG collapses all of that into 1 call regardless of chunk count.
     *   This is the primary speed improvement: 32 LLM calls → 3 LLM calls per query.
     *
     * Score semantics:
     *   5 = directly answers the question with specific facts
     *   4 = mostly answers, contains relevant content
     *   3 = partially relevant
     *   2 = vaguely related
     *   1 = irrelevant or just a topic mention / TOC entry
     *
     * Chunks scoring >= cragMinScore (default 4) are kept, sorted by score desc.
     * This eliminates the need for a separate re-ranking step — CRAG scores serve double duty.
     *
     * On any JSON parse failure: approves all chunks with score 3 (fail-open fallback).
     */
    private Map<String, Integer> batchCragEvaluate(List<String> chunks, String question,
                                                     QueryType type) {
        if (chunks.isEmpty()) return Collections.emptyMap();

        // Build numbered passage list (truncated to 300 chars to stay within context)
        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunks.get(i).substring(0, Math.min(chunks.get(i).length(), 300));
            passages.append("[").append(i + 1).append("] ").append(preview).append("\n\n");
        }

        // Rubric is adapted per query type so the LLM scores appropriately:
        // SYNTHESIS — individual passages contribute content, not full answers; 3+ should pass.
        // CODE_SEARCH — code examples demonstrate concepts; a usage example scores 4, not 2.
        // FACTOID — original rubric unchanged.
        String rubric = switch (type) {
            case SYNTHESIS ->
                "5 = rich content — multiple facts, definitions, or concepts\n" +
                "4 = solid content — at least one substantial concept or fact\n" +
                "3 = some content — partial concept or supporting detail\n" +
                "2 = minimal — mostly structural (headers, transitions, navigation)\n" +
                "1 = no useful content — TOC entry, page number, blank\n";
            case CODE_SEARCH ->
                "5 = directly shows a code example or precise definition of the concept\n" +
                "4 = shows related code or a partial explanation\n" +
                "3 = mentions the concept or shows adjacent usage\n" +
                "2 = vaguely related or an unrelated code example\n" +
                "1 = irrelevant\n";
            default ->
                "5 = directly answers with specific facts\n" +
                "4 = mostly answers, contains relevant content\n" +
                "3 = partially relevant\n" +
                "2 = vaguely related — mentions the topic but no specific content\n" +
                "1 = irrelevant — table of contents entry, heading only, or off-topic\n";
        };

        String prompt =
            "You are a relevance judge for a document Q&A system.\n" +
            "Rate each numbered passage for how well it answers the question.\n\n" +
            "Scoring:\n" +
            rubric + "\n" +
            "Question: " + question + "\n\n" +
            passages.toString() +
            "Output ONLY a JSON integer array with exactly " + chunks.size() + " scores.\n" +
            "Example for " + chunks.size() + " passages: " +
            buildExampleArray(chunks.size()) + "\n" +
            "No explanation. ONLY the array.";

        Map<String, Integer> scores = new LinkedHashMap<>();
        try {
            String raw = resolveChatModel().generate(prompt).trim();
            // Extract the integer array even if Gemma 2 adds preamble text
            Matcher m = Pattern.compile("\\[([\\d,\\s]+)\\]").matcher(raw);
            if (m.find()) {
                String[] parts = m.group(1).split(",");
                for (int i = 0; i < Math.min(parts.length, chunks.size()); i++) {
                    int score = Integer.parseInt(parts[i].trim());
                    scores.put(chunks.get(i), Math.max(1, Math.min(5, score)));
                }
            } else {
                System.out.println("[CRAG] Batch parse failed (no array found), approving all: " + raw.substring(0, Math.min(raw.length(), 100)));
                chunks.forEach(c -> scores.put(c, 3));
            }
        } catch (Exception e) {
            System.out.println("[CRAG] Batch evaluation exception, approving all: " + e.getMessage());
            chunks.forEach(c -> scores.put(c, 3));
        }

        // Fill missing entries (if LLM returned fewer scores than chunks)
        chunks.forEach(c -> scores.putIfAbsent(c, 3));
        return scores;
    }

    private String buildExampleArray(int n) {
        int[] sample = {4, 2, 5, 1, 3};
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(sample[i % sample.length]);
        }
        return sb.append("]").toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step D: Grounding score
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Lightweight proxy metric: fraction of meaningful answer words that appear in context.
     * If the model answers from the document, most content words will be present in context.
     * If it hallucinates from training data, words will be absent, producing a low score.
     * No extra LLM call required — runs in milliseconds.
     */
    private double computeGroundingScore(String answer, String context) {
        Set<String> stopwords = Set.of(
            "the","a","an","is","are","was","were","be","been","it","this","that","and","or",
            "but","in","on","at","to","for","of","with","as","by","from","has","have","had",
            "will","would","could","should","may","can","not","do","does","did","its","their",
            "they","we","you","i","he","she","if","also","only","then","very","just","more",
            "such","each","into","about","between","through","during","before","after","above",
            "below","use","used","using","make","made","making");

        String contextLower = context.toLowerCase();
        String[] words      = answer.toLowerCase().split("[\\W]+");

        long total = Arrays.stream(words)
            .filter(w -> w.length() > 3 && !stopwords.contains(w))
            .count();
        if (total == 0) return 1.0;

        long supported = Arrays.stream(words)
            .filter(w -> w.length() > 3 && !stopwords.contains(w) && contextLower.contains(w))
            .count();

        return Math.min(1.0, (double) supported / total);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Main pipeline
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Full CRAG pipeline: HyDE → Retrieve → Batch CRAG → Synthesize → Score.
     *
     * filenames: list of source_file values to search within.
     *   null or empty = search ALL ingested documents (cross-file mode)
     *   single entry  = single-file mode (original behavior)
     *   multiple      = multi-file mode using ChromaDB $in filter
     */
    public ChatResponse askAdvancedQuestion(String question, double minScore, int maxResults,
                                             List<String> filenames, String sessionId) {
        String fileLabel = (filenames == null || filenames.isEmpty()) ? "ALL" : String.join(", ", filenames);
        System.out.println("[QUERY] \"" + question + "\" | files=" + fileLabel + " | session=" + sessionId);
        System.out.println("[QUERY] Mode: " + queryExpansionMode);

        // Classify query to select adaptive thresholds and HyDE strategy
        QueryType queryType = classifyQuery(question);
        System.out.println("[QUERY] Type: " + queryType);

        // Relax similarity floor for synthesis/code queries:
        // HyDE prose vs code chunks and broad-coverage synthesis both sit at lower cosine distance.
        double effectiveMinScore = switch (queryType) {
            case SYNTHESIS   -> Math.min(minScore, relaxMinScoreSynthesis);
            case CODE_SEARCH -> Math.min(minScore, relaxMinScoreCode);
            default          -> Math.min(minScore, relaxMinScoreFactoid);
        };

        // Use a lower CRAG approval threshold for synthesis/code:
        // Score 3 = "partially relevant" is sufficient for chunks that contribute to a summary
        // or demonstrate a code concept, even if they don't fully define it.
        int effectiveCragMinScore = switch (queryType) {
            case SYNTHESIS   -> cragMinScoreSynthesis;
            case CODE_SEARCH -> cragMinScoreCode;
            default          -> cragMinScore;
        };

        ChatMemory memory = getSession(sessionId);

        // A: Query expansion (strategy varies by query type — see queryVariants)
        List<QueryVariant> variants = queryVariants(question, queryType);
        System.out.println("[QUERY] Variants: " + variants.size() +
            " | hyde-passage=" + variants.stream().filter(QueryVariant::isDocumentPassage).count());

        // B: Parallel retrieval across all variants
        List<EmbeddingMatch<TextSegment>> rawChunks =
            retrieveChunksParallel(variants, effectiveMinScore, maxResults, filenames);
        System.out.println("[RETRIEVAL] " + rawChunks.size() + " unique chunks retrieved" +
            " (minScore=" + effectiveMinScore + ")");

        if (rawChunks.isEmpty()) {
            System.out.println("[RETRIEVAL] FAILED — 0 chunks above minScore=" + effectiveMinScore +
                ". ChromaDB may be empty or all similarities below threshold.");
            String noData = buildNoDataMessage(filenames);
            memory.add(AiMessage.from(noData));
            return new ChatResponse(noData, List.of(), 0.0, 0);
        }

        // C: Batch CRAG — one LLM call for all chunks, returns relevance scores 1-5
        List<String> chunkTexts = rawChunks.stream()
            .map(m -> m.embedded().text())
            .collect(Collectors.toList());

        Map<String, Integer> scores = batchCragEvaluate(chunkTexts, question, queryType);

        // Filter to approved chunks (score >= effectiveCragMinScore), sort by score descending
        // Sorting by score replaces the separate rerankChunks() call from v2
        List<Map.Entry<String, Integer>> approved = scores.entrySet().stream()
            .filter(e -> e.getValue() >= effectiveCragMinScore)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .collect(Collectors.toList());

        System.out.printf("[CRAG] Approved: %d / %d (min score: %d, type: %s)%n",
                          approved.size(), chunkTexts.size(), effectiveCragMinScore, queryType);

        if (approved.isEmpty()) {
            System.out.println("[CRAG] FAILED — all " + chunkTexts.size() +
                " chunks scored below " + effectiveCragMinScore +
                ". Scores: " + scores.values());
            String noData = buildNoDataMessage(filenames);
            memory.add(AiMessage.from(noData));
            return new ChatResponse(noData, List.of(), 0.0, 0);
        }

        // Build a lookup map from chunk text → original EmbeddingMatch (for metadata)
        Map<String, EmbeddingMatch<TextSegment>> chunkIndex = rawChunks.stream()
            .collect(Collectors.toMap(m -> m.embedded().text(), m -> m, (a, b) -> a));

        // Build context string and citations list
        List<String> verifiedChunkTexts = new ArrayList<>();
        List<String> citations          = new ArrayList<>();
        StringBuilder contextBuilder    = new StringBuilder();

        for (int i = 0; i < approved.size(); i++) {
            String chunkText = approved.get(i).getKey();
            int    score     = approved.get(i).getValue();
            verifiedChunkTexts.add(chunkText);

            EmbeddingMatch<TextSegment> orig = chunkIndex.get(chunkText);
            String sourceFile = orig != null ? orig.embedded().metadata().getString("source_file") : null;
            String pageNum    = orig != null ? orig.embedded().metadata().getString("page_number")  : null;

            // Citation format: "chunk text [filename.pdf, Page 12]" — includes filename for multi-file
            String fileRef = sourceFile != null ? sourceFile : "unknown";
            String pageRef = (pageNum != null && !pageNum.isEmpty()) ? ", Page " + pageNum : "";
            citations.add(chunkText + " [" + fileRef + pageRef + "]");

            // Context passage label includes source file and page for the synthesis prompt
            contextBuilder.append("[Passage ").append(i + 1)
                          .append(" from ").append(fileRef);
            if (pageNum != null && !pageNum.isEmpty()) contextBuilder.append(", Page ").append(pageNum);
            contextBuilder.append(", relevance=").append(score).append("]\n")
                          .append(chunkText).append("\n\n---\n\n");
        }
        String context = contextBuilder.toString().trim();

        // D: Grounded synthesis — inline quote requirement prevents hallucination
        String finalPrompt =
            "You are a precise document analysis assistant.\n\n" +
            "STRICT RULES — follow all of them:\n" +
            "1. Answer using ONLY information from the Document Passages below.\n" +
            "2. Do NOT add anything from your training knowledge.\n" +
            "3. For every factual claim, quote 4–8 words from the passage that support it " +
            "(format: \"...quoted words...\").\n" +
            "4. If the passages lack sufficient information, say exactly: " +
            "\"The document does not provide enough detail to answer this question.\"\n" +
            "5. Never speculate, infer, or fill gaps.\n\n" +
            "Document Passages:\n" + context + "\n\n" +
            "Question: " + question + "\n\n" +
            "Answer (include inline passage quotes for each claim):";

        System.out.println("[SYNTHESIS] Generating from " + verifiedChunkTexts.size() + " verified chunks...");
        String finalAnswer = resolveChatModel().generate(finalPrompt);

        // E: Grounding score
        double groundingScore = computeGroundingScore(finalAnswer, context);
        System.out.printf("[GROUNDING] Score: %.2f (%s)%n", groundingScore,
            groundingScore >= 0.75 ? "HIGH" : groundingScore >= 0.50 ? "MEDIUM" : "LOW");

        memory.add(UserMessage.from(question));
        memory.add(AiMessage.from(finalAnswer));

        return new ChatResponse(finalAnswer, citations, groundingScore, verifiedChunkTexts.size());
    }

    private String buildNoDataMessage(List<String> filenames) {
        String scope = (filenames == null || filenames.isEmpty())
            ? "the uploaded documents"
            : String.join(", ", filenames);
        return "The document does not contain sufficient information to answer this question.\n\n" +
               "Searched in: " + scope + "\n\n" +
               "Suggestions:\n" +
               "• Lower the Similarity Threshold in Settings (try 0.55)\n" +
               "• Rephrase using words likely in the document\n" +
               "• Enable 'ALL DOCS' mode if the answer may be in a different uploaded file\n" +
               "• Check that the relevant section was ingested (re-upload if needed)";
    }

    public void clearHistory(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) entry.memory.clear();
        System.out.println("[Session] Cleared: " + sessionId);
    }

    private ChatLanguageModel resolveChatModel() {
        try {
            return inferenceAdapter.resolveModel()
                    .map(model -> (ChatLanguageModel) model)
                    .orElse(llm);
        } catch (Exception e) {
            System.out.println("[QUERY] Distributed model resolution failed, using local: " + e.getMessage());
            return llm;
        }
    }
}
