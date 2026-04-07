package com.example.local_notebooklm.chat.evaluator;

import com.example.local_notebooklm.chat.domain.QueryType;
import com.example.local_notebooklm.inference.distributed.router.InferenceServiceAdapter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CragEvaluatorService {

    private final ChatLanguageModel llm;
    private final InferenceServiceAdapter inferenceAdapter;

    public CragEvaluatorService(ChatLanguageModel llm, InferenceServiceAdapter inferenceAdapter) {
        this.llm = llm;
        this.inferenceAdapter = inferenceAdapter;
    }

    public Map<String, Integer> batchCragEvaluate(List<String> chunks, String question, QueryType type) {
        if (chunks.isEmpty()) return Collections.emptyMap();

        StringBuilder passages = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            String preview = chunks.get(i).substring(0, Math.min(chunks.get(i).length(), 300));
            passages.append("[").append(i + 1).append("] ").append(preview).append("\n\n");
        }

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
                        "Scoring:\n" + rubric + "\n" +
                        "Question: " + question + "\n\n" + passages.toString() +
                        "Output ONLY a JSON integer array with exactly " + chunks.size() + " scores.\n" +
                        "Example for " + chunks.size() + " passages: " + buildExampleArray(chunks.size()) + "\n" +
                        "No explanation. ONLY the array.";

        Map<String, Integer> scores = new LinkedHashMap<>();
        try {
            String raw = resolveChatModel().generate(prompt).trim();
            Matcher m = Pattern.compile("\\[([\\d,\\s]+)\\]").matcher(raw);
            if (m.find()) {
                String[] parts = m.group(1).split(",");
                for (int i = 0; i < Math.min(parts.length, chunks.size()); i++) {
                    int score = Integer.parseInt(parts[i].trim());
                    scores.put(chunks.get(i), Math.max(1, Math.min(5, score)));
                }
            } else {
                System.out.println("[CRAG] Batch parse failed, approving all.");
                chunks.forEach(c -> scores.put(c, 3));
            }
        } catch (Exception e) {
            System.out.println("[CRAG] Batch evaluation exception, approving all: " + e.getMessage());
            chunks.forEach(c -> scores.put(c, 3));
        }

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

    private ChatLanguageModel resolveChatModel() {
        try { return inferenceAdapter.resolveModel().map(model -> (ChatLanguageModel) model).orElse(llm); }
        catch (Exception e) { return llm; }
    }
}