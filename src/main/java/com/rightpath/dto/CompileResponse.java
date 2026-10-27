package com.rightpath.dto;

import lombok.Data;

@Data
public class CompileResponse {
    private String output;
    private String error;
    private long executionTimeMs;
}
