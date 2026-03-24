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
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChatbotService {

    private final ChatLanguageModel llama3;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final ChatMemory chatMemory;

    public ChatbotService(ChatLanguageModel chatLanguageModel,
                          EmbeddingModel embeddingModel,
                          EmbeddingStore<TextSegment> embeddingStore) {
        this.llama3 = chatLanguageModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.chatMemory = MessageWindowChatMemory.withMaxMessages(10);
    }

    public ChatResponse askAdvancedQuestion(String question) {
        System.out.println("User asked: " + question);

        // 1. Embed the Question
        dev.langchain4j.data.embedding.Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Retrieve using the NEW LangChain4j Search API
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(5) // Pull top 5 chunks
                .minScore(0.70) // Must be at least 70% relevant
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> rawChunks = searchResult.matches();

        // 3. CRAG Evaluator using Java Text Blocks (No more messy concatenation!)
        List<String> verifiedChunks = new ArrayList<>();
        List<String> citations = new ArrayList<>();

        System.out.println("CRAG: Evaluating " + rawChunks.size() + " retrieved chunks...");

        for (EmbeddingMatch<TextSegment> match : rawChunks) {
            String chunkText = match.embedded().text();

            String evalPrompt = """
                    You are a strict grader. Does the following document contain information relevant to answering the question: '%s'?
                    
                    Document: %s
                    
                    Reply with EXACTLY 'YES' or 'NO'. Do not explain.
                    """.formatted(question, chunkText);

            String grade = llama3.generate(evalPrompt).trim();

            if (grade.toUpperCase().contains("YES")) {
                verifiedChunks.add(chunkText);
                citations.add(chunkText);
                System.out.println("CRAG: Chunk Approved ✅");
            } else {
                System.out.println("CRAG: Chunk Rejected ❌");
            }
        }

        // 4. Fallback for Zero Hallucinations
        if (verifiedChunks.isEmpty()) {
            String noDataResponse = "I'm sorry, the uploaded document does not contain the answer to this question.";
            chatMemory.add(AiMessage.from(noDataResponse));
            return new ChatResponse(noDataResponse, List.of("No relevant sources found."));
        }

        // 5. Build the final context
        String context = String.join("\n\n---\n\n", verifiedChunks);

        // 6. Generate the Final Answer using a Text Block
        String finalPrompt = """
                You are an AI assistant. Use ONLY the provided Verified Document Context to answer the User's question.
                
                Verified Document Context:
                %s
                
                Question: %s
                """.formatted(context, question);

        System.out.println("Generating final synthesized answer...");
        String finalAnswer = llama3.generate(finalPrompt);

        // Save the clean question and answer to memory for future context
        chatMemory.add(UserMessage.from(question));
        chatMemory.add(AiMessage.from(finalAnswer));

        return new ChatResponse(finalAnswer, citations);
    }
}