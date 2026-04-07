package com.example.local_notebooklm.chat.orchestrator;

import com.example.local_notebooklm.dto.ChatResponse;
import com.example.local_notebooklm.chat.classifier.QueryClassifierService;
import com.example.local_notebooklm.chat.domain.QueryType;
import com.example.local_notebooklm.chat.domain.QueryVariant;
import com.example.local_notebooklm.chat.evaluator.CragEvaluatorService;
import com.example.local_notebooklm.chat.evaluator.GroundingEvaluatorService;
import com.example.local_notebooklm.chat.expansion.QueryExpansionService;
import com.example.local_notebooklm.chat.memory.ChatMemoryService;
import com.example.local_notebooklm.chat.retriever.VectorRetrievalService;
import com.example.local_notebooklm.inference.distributed.router.InferenceServiceAdapter;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatOrchestrator {

    private final QueryClassifierService classifier;
    private final QueryExpansionService expander;
    private final VectorRetrievalService retriever;
    private final CragEvaluatorService crag;
    private final GroundingEvaluatorService grounder;
    private final ChatMemoryService memoryService;
    private final ChatLanguageModel llm;
    private final InferenceServiceAdapter inferenceAdapter;

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

    public ChatOrchestrator(QueryClassifierService classifier, QueryExpansionService expander,
                            VectorRetrievalService retriever, CragEvaluatorService crag,
                            GroundingEvaluatorService grounder, ChatMemoryService memoryService,
                            ChatLanguageModel llm, InferenceServiceAdapter inferenceAdapter) {
        this.classifier = classifier;
        this.expander = expander;
        this.retriever = retriever;
        this.crag = crag;
        this.grounder = grounder;
        this.memoryService = memoryService;
        this.llm = llm;
        this.inferenceAdapter = inferenceAdapter;
    }

    public ChatResponse askAdvancedQuestion(String question, double minScore, int maxResults,
                                            List<String> filenames, String sessionId) {

        String fileLabel = (filenames == null || filenames.isEmpty()) ? "ALL" : String.join(", ", filenames);
        System.out.println("[QUERY] \"" + question + "\" | files=" + fileLabel + " | session=" + sessionId);
        System.out.println("[QUERY] Mode: " + expander.getQueryExpansionMode());

        QueryType queryType = classifier.classifyQuery(question);
        System.out.println("[QUERY] Type: " + queryType);

        double effectiveMinScore = switch (queryType) {
            case SYNTHESIS   -> Math.min(minScore, relaxMinScoreSynthesis);
            case CODE_SEARCH -> Math.min(minScore, relaxMinScoreCode);
            default          -> Math.min(minScore, relaxMinScoreFactoid);
        };

        int effectiveCragMinScore = switch (queryType) {
            case SYNTHESIS   -> cragMinScoreSynthesis;
            case CODE_SEARCH -> cragMinScoreCode;
            default          -> cragMinScore;
        };

        ChatMemory memory = memoryService.getSession(sessionId);

        List<QueryVariant> variants = expander.getVariants(question, queryType);
        System.out.println("[QUERY] Variants: " + variants.size() +
                " | hyde-passage=" + variants.stream().filter(QueryVariant::isDocumentPassage).count());

        List<EmbeddingMatch<TextSegment>> rawChunks = retriever.retrieveChunksParallel(variants, effectiveMinScore, maxResults, filenames);
        System.out.println("[RETRIEVAL] " + rawChunks.size() + " unique chunks retrieved (minScore=" + effectiveMinScore + ")");

        if (rawChunks.isEmpty()) {
            System.out.println("[RETRIEVAL] FAILED — 0 chunks above minScore=" + effectiveMinScore);
            return handleNoData(filenames, memory);
        }

        List<String> chunkTexts = rawChunks.stream().map(m -> m.embedded().text()).collect(Collectors.toList());
        Map<String, Integer> scores = crag.batchCragEvaluate(chunkTexts, question, queryType);

        List<Map.Entry<String, Integer>> approved = scores.entrySet().stream()
                .filter(e -> e.getValue() >= effectiveCragMinScore)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toList());

        System.out.printf("[CRAG] Approved: %d / %d (min score: %d, type: %s)%n",
                approved.size(), chunkTexts.size(), effectiveCragMinScore, queryType);

        if (approved.isEmpty()) {
            System.out.println("[CRAG] FAILED — Scores: " + scores.values());
            return handleNoData(filenames, memory);
        }

        Map<String, EmbeddingMatch<TextSegment>> chunkIndex = rawChunks.stream()
                .collect(Collectors.toMap(m -> m.embedded().text(), m -> m, (a, b) -> a));

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

            String fileRef = sourceFile != null ? sourceFile : "unknown";
            String pageRef = (pageNum != null && !pageNum.isEmpty()) ? ", Page " + pageNum : "";
            citations.add(chunkText + " [" + fileRef + pageRef + "]");

            contextBuilder.append("[Passage ").append(i + 1)
                    .append(" from ").append(fileRef);
            if (pageNum != null && !pageNum.isEmpty()) contextBuilder.append(", Page ").append(pageNum);
            contextBuilder.append(", relevance=").append(score).append("]\n")
                    .append(chunkText).append("\n\n---\n\n");
        }

        String context = contextBuilder.toString().trim();

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

        double groundingScore = grounder.computeGroundingScore(finalAnswer, context);
        System.out.printf("[GROUNDING] Score: %.2f (%s)%n", groundingScore,
                groundingScore >= 0.75 ? "HIGH" : groundingScore >= 0.50 ? "MEDIUM" : "LOW");

        memory.add(UserMessage.from(question));
        memory.add(AiMessage.from(finalAnswer));

        return new ChatResponse(finalAnswer, citations, groundingScore, verifiedChunkTexts.size());
    }

    private ChatResponse handleNoData(List<String> filenames, ChatMemory memory) {
        String scope = (filenames == null || filenames.isEmpty()) ? "the uploaded documents" : String.join(", ", filenames);
        String noData = "The document does not contain sufficient information to answer this question.\n\n" +
                "Searched in: " + scope + "\n\n" +
                "Suggestions:\n" +
                "• Lower the Similarity Threshold in Settings (try 0.55)\n" +
                "• Rephrase using words likely in the document\n" +
                "• Enable 'ALL DOCS' mode if the answer may be in a different uploaded file\n" +
                "• Check that the relevant section was ingested (re-upload if needed)";
        memory.add(AiMessage.from(noData));
        return new ChatResponse(noData, List.of(), 0.0, 0);
    }

    private ChatLanguageModel resolveChatModel() {
        try {
            return inferenceAdapter.resolveModel().map(model -> (ChatLanguageModel) model).orElse(llm);
        } catch (Exception e) {
            System.out.println("[QUERY] Distributed model resolution failed, using local: " + e.getMessage());
            return llm;
        }
    }
}