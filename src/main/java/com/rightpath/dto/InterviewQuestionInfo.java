package com.rightpath.dto;

public class InterviewQuestionInfo {
    private final String text;
    private final String uniqueId;
    private final String level;
    private final String category;
    private final Long createdAt;   // Unix epoch milliseconds

    public InterviewQuestionInfo(String text, String uniqueId, String level, String category, Long createdAt) {
        this.text = text;
        this.uniqueId = uniqueId;
        this.level = level;
        this.category = category;
        this.createdAt = createdAt;
    }

    public String getText() { return text; }
    public String getUniqueId() { return uniqueId; }
    public String getLevel() { return level; }
    public String getCategory() { return category; }
    public Long getCreatedAt() { return createdAt; }
}