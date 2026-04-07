package com.example.local_notebooklm.chat.expansion;

import com.example.local_notebooklm.chat.domain.QueryType;
import com.example.local_notebooklm.chat.domain.QueryVariant;
import com.example.local_notebooklm.inference.distributed.router.InferenceServiceAdapter;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class QueryExpansionService {

    private final ChatLanguageModel llm;
    private final InferenceServiceAdapter inferenceAdapter;

    @Value("${rag.query-expansion.mode:hyde}")
    private String queryExpansionMode;

    public QueryExpansionService(ChatLanguageModel llm, InferenceServiceAdapter inferenceAdapter) {
        this.llm = llm;
        this.inferenceAdapter = inferenceAdapter;
    }

    public List<QueryVariant> getVariants(String question, QueryType type) {
        return switch (queryExpansionMode) {
            case "hyde" -> switch (type) {
                case SYNTHESIS -> List.of(
                        new QueryVariant(question, false),
                        new QueryVariant("contents topics summary overview", false),
                        new QueryVariant("main points key concepts introduction", false));
                case CODE_SEARCH -> List.of(
                        new QueryVariant(generateHypotheticalCodePassage(question), true),
                        new QueryVariant(question, false));
                default -> List.of(new QueryVariant(generateHypotheticalPassage(question), true));
            };
            case "multi-query" -> expandQuery(question).stream()
                    .map(q -> new QueryVariant(q, false))
                    .collect(Collectors.toList());
            default -> List.of(new QueryVariant(question, false));
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

    private ChatLanguageModel resolveChatModel() {
        try {
            return inferenceAdapter.resolveModel().map(model -> (ChatLanguageModel) model).orElse(llm);
        } catch (Exception e) {
            return llm;
        }
    }

    public String getQueryExpansionMode() {
        return queryExpansionMode;
    }
}