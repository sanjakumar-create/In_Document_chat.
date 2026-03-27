package com.example.local_notebooklm.dto;

import java.util.List;

/**
 * Response payload for /api/chat/ask.
 *
 * groundingScore: 0.0–1.0 — fraction of meaningful answer words found in the
 * retrieved context. Closer to 1.0 means better grounding; lower values signal
 * potential hallucination.
 *
 * chunkCount: number of document chunks that passed CRAG evaluation and were
 * used to generate the answer. Displayed in the UI as "N chunks verified".
 * A count of 0 means the fallback "no information found" message was returned.
 */
public class ChatResponse {
    private final String       answer;
    private final List<String> citations;
    private final double       groundingScore;
    private final int          chunkCount;

    public ChatResponse(String answer, List<String> citations, double groundingScore, int chunkCount) {
        this.answer         = answer;
        this.citations      = citations;
        this.groundingScore = groundingScore;
        this.chunkCount     = chunkCount;
    }

    public String       getAnswer()         { return answer;         }
    public List<String> getCitations()      { return citations;      }
    public double       getGroundingScore() { return groundingScore; }
    public int          getChunkCount()     { return chunkCount;     }
}
