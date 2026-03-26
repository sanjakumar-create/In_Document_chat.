package com.example.local_notebooklm.service;

import com.example.local_notebooklm.dto.ChatResponse;
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
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class ChatbotService {

    private final ChatLanguageModel llm;
    private final EmbeddingModel    embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // ── Per-session isolated memory ───────────────────────────────────────────
    // Each browser tab / user gets its own 20-message rolling window.
    // Sessions idle for > 30 minutes are automatically evicted.
    private static class SessionEntry {
        final ChatMemory memory      = MessageWindowChatMemory.withMaxMessages(20);
        volatile long    lastAccessed = System.currentTimeMillis();
    }
    private final ConcurrentHashMap<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictionScheduler;

    public ChatbotService(ChatLanguageModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.llm            = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;

        this.evictionScheduler = Executors.newSingleThreadScheduledExecutor();
        evictionScheduler.scheduleAtFixedRate(this::evictExpiredSessions, 5, 5, TimeUnit.MINUTES);
    }

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

    // ── Step A: Multi-query expansion ────────────────────────────────────────
    // Why: a single embedding query only retrieves chunks that are semantically
    // similar to the exact wording used. If the user asks "prerequisites" but
    // the document says "before enrolling, students should...", the single query
    // misses it. Generating 3 paraphrases and unioning the results captures
    // much more of the relevant content.
    private List<String> expandQuery(String question) {
        String prompt =
            "Generate exactly 3 different phrasings of this question for document search.\n" +
            "Use different vocabulary but ask for the same information.\n" +
            "Output exactly 3 lines. No numbering, no bullet points, no explanations.\n\n" +
            "Question: " + question;
        try {
            String[] lines = llm.generate(prompt).trim().split("\n");
            List<String> variants = Arrays.stream(lines)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() > 5)
                .limit(3)
                .collect(Collectors.toList());
            variants.add(question);  // always include the original
            System.out.println("[QUERY] Expanded to " + variants.size() + " variants");
            return variants;
        } catch (Exception e) {
            System.out.println("[QUERY] Expansion failed, using original: " + e.getMessage());
            return List.of(question);
        }
    }

    // ── Step B: Retrieve chunks for one query ─────────────────────────────────
    // The instruction prefix "Represent this sentence for searching relevant passages: "
    // is required by mxbai-embed-large. This model is trained for ASYMMETRIC retrieval:
    // the query and the document passage go through different encoding paths, which
    // dramatically improves retrieval accuracy over symmetric models like nomic-embed-text.
    private List<EmbeddingMatch<TextSegment>> retrieveChunks(
            String query, double minScore, int maxResults, String filename) {

        String prefixedQuery = "Represent this sentence for searching relevant passages: " + query;
        dev.langchain4j.data.embedding.Embedding queryEmbedding =
            embeddingModel.embed(prefixedQuery).content();

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
            .queryEmbedding(queryEmbedding)
            .maxResults(maxResults)
            .minScore(minScore)
            .filter(metadataKey("source_file").isEqualTo(filename))
            .build();
        return embeddingStore.search(request).matches();
    }

    // ── Step C: CRAG — strict two-gate evaluation ─────────────────────────────
    // Why two gates?
    // Gate 1 rejects vague topic mentions and TOC entries.
    // Gate 2 confirms that a specific extractable fact exists in the passage.
    // Together they prevent the most common failure mode: a TOC line saying
    // "Regular Expressions .... 47" passing the evaluation and causing the model
    // to synthesise regex content from its training knowledge.
    private boolean cragApprove(String chunk, String question) {
        // Gate 1: Specific content, not just a topic mention
        String gate1 = String.format(
            "You are a strict document relevance judge.\n\n" +
            "Question: %s\n\n" +
            "Document Passage:\n%s\n\n" +
            "Does this passage contain SPECIFIC FACTUAL INFORMATION that directly helps answer the question?\n" +
            "Rules:\n" +
            "- A table of contents entry scores NO\n" +
            "- A section heading without explanation scores NO\n" +
            "- A passage that only mentions a topic name without explaining it scores NO\n" +
            "- A passage with actual content, definitions, or explanations scores YES\n\n" +
            "Reply with EXACTLY one word: YES or NO",
            question, chunk);

        String grade1 = llm.generate(gate1).trim().toUpperCase();
        if (!grade1.equals("YES")) return false;

        // Gate 2: Extractable specific fact
        String gate2 = String.format(
            "Based ONLY on the following passage, does it contain an extractable specific fact " +
            "or explanation that answers: \"%s\"?\n\n" +
            "Passage:\n%s\n\n" +
            "Reply with EXACTLY one word: YES or NO",
            question, chunk);

        return llm.generate(gate2).trim().toUpperCase().equals("YES");
    }

    // ── Step D: Re-rank approved chunks by relevance score ───────────────────
    // After CRAG approves chunks, they arrive in vector-similarity order —
    // not necessarily the most relevant first. The synthesis model gives more
    // weight to content at the start of its context window (primacy effect),
    // so placing the highest-relevance chunks first produces better answers.
    private List<String> rerankChunks(List<String> chunks, String question) {
        if (chunks.size() <= 2) return chunks;

        record Scored(String text, int score) {}
        List<Scored> scored = new ArrayList<>();

        for (String chunk : chunks) {
            String scorePrompt = String.format(
                "Rate how directly this passage answers the question.\n" +
                "Question: %s\n" +
                "Passage: %s\n\n" +
                "Reply with ONLY a single digit: 5 (directly answers), 4 (mostly), " +
                "3 (partially), 2 (vaguely related), 1 (irrelevant).",
                question, chunk.substring(0, Math.min(chunk.length(), 600)));
            try {
                String raw = llm.generate(scorePrompt).trim().replaceAll("[^1-5]", "");
                int s = raw.isEmpty() ? 3 : Integer.parseInt(String.valueOf(raw.charAt(0)));
                scored.add(new Scored(chunk, s));
            } catch (Exception e) {
                scored.add(new Scored(chunk, 3));
            }
        }

        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        return scored.stream().map(Scored::text).collect(Collectors.toList());
    }

    // ── Step E: Grounding score ───────────────────────────────────────────────
    // Measures what fraction of the meaningful words in the final answer also
    // appear in the retrieved context. If the model is answering from the
    // document, most content words will match. If it hallucinates from training
    // knowledge, many words will NOT be in the context, producing a low score.
    // This is a lightweight proxy — not a perfect NLI check — but it runs in
    // milliseconds with no extra model call.
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

    // ── Main pipeline ─────────────────────────────────────────────────────────
    public ChatResponse askAdvancedQuestion(String question, double minScore, int maxResults,
                                             String filename, String sessionId) {
        System.out.println("[QUERY] \"" + question + "\" | file=" + filename + " | session=" + sessionId);
        ChatMemory memory = getSession(sessionId);

        // A: Multi-query expansion
        List<String> queryVariants = expandQuery(question);

        // B: Retrieve for all variants, deduplicate by chunk text
        // LinkedHashMap preserves insertion order while deduplicating
        Map<String, EmbeddingMatch<TextSegment>> uniqueChunks = new LinkedHashMap<>();
        for (String variant : queryVariants) {
            for (EmbeddingMatch<TextSegment> match : retrieveChunks(variant, minScore, maxResults, filename)) {
                uniqueChunks.putIfAbsent(match.embedded().text(), match);
            }
        }
        List<EmbeddingMatch<TextSegment>> rawChunks = new ArrayList<>(uniqueChunks.values());
        System.out.println("[RETRIEVAL] " + rawChunks.size() + " unique chunks from " +
                           queryVariants.size() + " queries");

        // C: CRAG two-gate evaluation
        List<String> verifiedChunks = new ArrayList<>();
        List<String> citations      = new ArrayList<>();

        for (EmbeddingMatch<TextSegment> match : rawChunks) {
            String chunkText = match.embedded().text();
            String pageNum   = match.embedded().metadata().getString("page_number");
            String pageLabel = (pageNum != null && !pageNum.isEmpty()) ? " [Page " + pageNum + "]" : "";

            boolean approved = cragApprove(chunkText, question);
            if (approved) {
                verifiedChunks.add(chunkText);
                citations.add(chunkText + pageLabel);
                System.out.println("[CRAG] APPROVED ✅" + pageLabel);
            } else {
                System.out.println("[CRAG] REJECTED ❌" + pageLabel);
            }
        }

        // Fallback: no verified chunks
        if (verifiedChunks.isEmpty()) {
            String noData =
                "The uploaded document does not contain sufficient information to answer this question.\n\n" +
                "Suggestions:\n" +
                "• Lower the Similarity Threshold in Settings (try 0.55)\n" +
                "• Rephrase using words from the document\n" +
                "• Check that the relevant section was ingested (re-upload if needed)";
            memory.add(AiMessage.from(noData));
            return new ChatResponse(noData, List.of(), 0.0);
        }

        // D: Re-rank
        verifiedChunks = rerankChunks(verifiedChunks, question);

        // Build context string with page references
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < verifiedChunks.size(); i++) {
            EmbeddingMatch<TextSegment> orig = uniqueChunks.get(verifiedChunks.get(i));
            String pn = (orig != null) ? orig.embedded().metadata().getString("page_number") : null;
            contextBuilder.append("[Passage ").append(i + 1);
            if (pn != null && !pn.isEmpty()) contextBuilder.append(", Page ").append(pn);
            contextBuilder.append("]\n").append(verifiedChunks.get(i)).append("\n\n---\n\n");
        }
        String context = contextBuilder.toString().trim();

        // E: Grounded synthesis with mandatory inline citations
        // The inline quote requirement forces the model to anchor every claim in the
        // retrieved text. If it cannot find supporting words to quote, it cannot make
        // the claim — this is the single most effective prompt-level hallucination guard.
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

        System.out.println("[SYNTHESIS] Generating from " + verifiedChunks.size() + " verified chunks...");
        String finalAnswer = llm.generate(finalPrompt);

        // F: Grounding score
        double groundingScore = computeGroundingScore(finalAnswer, context);
        System.out.printf("[GROUNDING] Score: %.2f (%s)%n", groundingScore,
            groundingScore >= 0.75 ? "HIGH" : groundingScore >= 0.50 ? "MEDIUM" : "LOW");

        memory.add(UserMessage.from(question));
        memory.add(AiMessage.from(finalAnswer));

        return new ChatResponse(finalAnswer, citations, groundingScore);
    }

    public void clearHistory(String sessionId) {
        SessionEntry entry = sessions.get(sessionId);
        if (entry != null) entry.memory.clear();
        System.out.println("[Session] Cleared: " + sessionId);
    }
}
