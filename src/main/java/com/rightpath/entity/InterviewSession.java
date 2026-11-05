package com.rightpath.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.rightpath.dto.InterviewQuestionInfo;

import lombok.Data;

@Data
public class InterviewSession {

    private String jobPrefix;
    private String email;

    private List<Map<String, String>> messages = new ArrayList<>();
    private String lastAnswer;
    private boolean completed = false;

    private List<InterviewQuestionInfo> questions;     
    private int currentQuestionIndex = 0;
    private String resumeSummary;
    
    
    // new field: accumulates Q&A in a format like "Q: ...\nA: ...\n"
    private StringBuilder qaHistory;

    // Constructor (or you can initialize in getter)
    public InterviewSession() {
        this.qaHistory = new StringBuilder();
    }

    // Add a Q&A pair to the history
    public void appendQAPair(String question, String answer) {
        qaHistory.append("Q: ").append(question).append("\n");
        qaHistory.append("A: ").append(answer).append("\n");
    }

    // Retrieve the full history as a String
    public String getQaHistoryAsString() {
        return qaHistory.toString();
    }
    
 // In InterviewSession.java
    private Map<Integer, Boolean> retryUsed = new HashMap<>();

    public Map<Integer, Boolean> getRetryUsed() {
        return retryUsed;
    }
}