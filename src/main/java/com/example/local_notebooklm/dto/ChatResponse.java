package com.example.local_notebooklm.dto;

import java.util.List;

public class ChatResponse {
    private String answer;
    private List<String> citations;

    public ChatResponse(String answer, List<String> citations) {
        this.answer = answer;
        this.citations = citations;
    }

    // Getters so Spring Boot can serialize it to JSON
    public String getAnswer() { return answer; }
    public List<String> getCitations() { return citations; }
}