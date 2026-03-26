package com.example.local_notebooklm.dto;

import java.util.List;

/**
 * Response payload for /api/chat/ask.
 * groundingScore: 0.0–1.0 — fraction of meaningful answer words found in the
 * retrieved context. Closer to 1.0 means better grounding; lower values signal
 * potential hallucination.
 */
public class ChatResponse {
    private final String       answer;
    private final List<String> citations;
    private final double       groundingScore;

    public ChatResponse(String answer, List<String> citations, double groundingScore) {
        this.answer         = answer;
        this.citations      = citations;
        this.groundingScore = groundingScore;
    }

    public String       getAnswer()         { return answer;         }
    public List<String> getCitations()      { return citations;      }
    public double       getGroundingScore() { return groundingScore; }
}