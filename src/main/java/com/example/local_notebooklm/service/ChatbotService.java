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
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;
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

    /**
     * Asks the AI a question using the CRAG pipeline.
     *
     * @param question   The user's question.
     * @param minScore   Minimum cosine similarity for a chunk to be retrieved (0.0–1.0).
     * @param maxResults Maximum number of chunks to pull from ChromaDB before evaluation.
     */
    public ChatResponse askAdvancedQuestion(String question, double minScore, int maxResults, String filename) {
        System.out.println("User asked: " + question + " about file: " + filename);

        // 1. Embed the question into a vector
        dev.langchain4j.data.embedding.Embedding questionEmbedding = embeddingModel.embed(question).content();

        // 2. Search ChromaDB for the most relevant chunks using the METADATA FILTER
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(metadataKey("source_file").isEqualTo(filename)) // 🔒 THE BOUNCER!
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> rawChunks = searchResult.matches();

        // 3. CRAG Evaluator — each chunk is graded YES/NO before being used
        List<String> verifiedChunks = new ArrayList<>();
        List<String> citations = new ArrayList<>();

        System.out.println("CRAG: Evaluating " + rawChunks.size() + " retrieved chunks...");

        for (EmbeddingMatch<TextSegment> match : rawChunks) {
            String chunkText = match.embedded().text();

            // Inside ChatbotService.java
            String evalPrompt = """
        You are an AI evaluator. Look at the following document snippet. 
        Does this snippet contain ANY partial or complete information that would be helpful in answering the user's question: '%s'?
        
        Document Snippet: %s
        
        If it contains useful facts, reply EXACTLY with 'YES'. If it is completely irrelevant, reply EXACTLY with 'NO'. Do not explain your reasoning.
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

        // 4. Fallback — no verified chunks means the document doesn't contain the answer
        if (verifiedChunks.isEmpty()) {
            String noDataResponse = "I'm sorry, the uploaded document does not contain the answer to this question.";
            chatMemory.add(AiMessage.from(noDataResponse));
            return new ChatResponse(noDataResponse, List.of());
        }

        // 5. Build the verified context string
        String context = String.join("\n\n---\n\n", verifiedChunks);

        // 6. Generate the final answer using only verified facts
        String finalPrompt = """
                You are an AI assistant. Use ONLY the provided Verified Document Context to answer the User's question.

                Verified Document Context:
                %s

                Question: %s
                """.formatted(context, question);

        System.out.println("Generating final synthesized answer...");
        String finalAnswer = llama3.generate(finalPrompt);

        chatMemory.add(UserMessage.from(question));
        chatMemory.add(AiMessage.from(finalAnswer));

        return new ChatResponse(finalAnswer, citations);
    }

    /** Clears the server-side rolling conversation window. */
    public void clearHistory() {
        chatMemory.clear();
        System.out.println("Conversation memory cleared.");
    }
}
