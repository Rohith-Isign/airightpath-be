package com.rightpath.dto;

import lombok.Data;

@Data
public class CompileRequest {
    private String code;
    private String language;   // e.g., "java", "python"
    private String stdin;      // optional input
}