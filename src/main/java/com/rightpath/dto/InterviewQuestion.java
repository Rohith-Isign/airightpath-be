package com.rightpath.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewQuestion {
    private int id;
    private String uniqueId;
    private String level;
    private String category;
    private String question;
    private Long createdAt;   // Unix epoch milliseconds (or seconds)
}