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
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChatbotService {

    private final ChatLanguageModel llama3;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    // FEATURE 1: Conversational Memory (Remembers the last 10 messages)
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

        // Add user's new question to the memory
        chatMemory.add(UserMessage.from(question));

        // 1. Retrieve raw chunks from ChromaDB
        dev.langchain4j.data.embedding.Embedding questionEmbedding = embeddingModel.embed(question).content();
        List<EmbeddingMatch<TextSegment>> rawChunks = embeddingStore.findRelevant(questionEmbedding, 3, 0.6);

        // FEATURE 2: Corrective RAG (CRAG) Evaluator
        List<String> verifiedChunks = new ArrayList<>();
        List<String> citations = new ArrayList<>();

        System.out.println("CRAG: Evaluating " + rawChunks.size() + " retrieved chunks...");

        for (EmbeddingMatch<TextSegment> match : rawChunks) {
            String chunkText = match.embedded().text();

            // Ask the LLM to grade the chunk strictly
            String evalPrompt = String.format(
                    "You are a strict grader. Does the following document contain information relevant to answering the question: '%s'?\n" +
                            "Document: %s\n" +
                            "Reply with EXACTLY 'YES' or 'NO'. Do not explain.",
                    question, chunkText
            );

            String grade = llama3.generate(evalPrompt).trim();

            if (grade.toUpperCase().contains("YES")) {
                verifiedChunks.add(chunkText);
                citations.add(chunkText); // Add to our citations list
                System.out.println("CRAG: Chunk Approved ✅");
            } else {
                System.out.println("CRAG: Chunk Rejected ❌");
            }
        }

        // 3. Fallback: If CRAG rejected all chunks (Zero Hallucination Guarantee)
        if (verifiedChunks.isEmpty()) {
            String noDataResponse = "I'm sorry, the uploaded document does not contain the answer to this question.";
            chatMemory.add(AiMessage.from(noDataResponse));
            return new ChatResponse(noDataResponse, List.of("No relevant sources found."));
        }

        // 4. Build the final context from ONLY the verified chunks
        String context = String.join("\n\n---\n\n", verifiedChunks);

        // 5. Build the final prompt using Context AND Chat History
        String memoryContext = chatMemory.messages().stream()
                .map(m -> m.type() + ": " + m.text())
                .collect(Collectors.joining("\n"));

        String finalPrompt = "You are an AI assistant. Use the provided Context to answer the User's latest question.\n" +
                "Here is the recent conversation history for context:\n" + memoryContext + "\n\n" +
                "Verified Document Context:\n" + context + "\n\n" +
                "Answer the latest question clearly and concisely.";

        System.out.println("Generating final synthesized answer...");
        String finalAnswer = llama3.generate(finalPrompt);

        // Save AI's answer to memory for the next question
        chatMemory.add(AiMessage.from(finalAnswer));

        // FEATURE 3: Return the structured response with Citations
        return new ChatResponse(finalAnswer, citations);
    }
}