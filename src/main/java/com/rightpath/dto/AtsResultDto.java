package com.rightpath.dto;

/**
 * Result of screening a single resume against a job description.
 *
 * @param fileName original filename of the uploaded resume
 * @param email    email address extracted from the resume (null if none found)
 * @param score    weighted ATS score (percentage, 0–100), rounded to 2 decimals
 * @param matched  whether the score met the shortlisting threshold
 */
public record AtsResultDto(
        String fileName,
        String email,
        double score,
        boolean matched
) {
}
