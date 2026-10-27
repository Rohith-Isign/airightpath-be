package com.rightpath.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Raw, unvalidated query parameters for the paginated job listing.
 *
 * <p>Every field is nullable and holds the value exactly as it arrived on the
 * request; defaulting, clamping and parsing happen in the service layer so the
 * rules are testable in one place.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobPostSearchRequest {

    /** Zero-based page index; {@code null} means 0. */
    private Integer page;

    /** Page size; {@code null} means 20, values above 100 are clamped to 100. */
    private Integer size;

    /**
     * {@code field,direction} pairs, e.g. {@code applicationDeadline,asc} or
     * {@code createdAt,desc}; {@code null} means {@code applicationDeadline,asc}.
     */
    private String sort;

    /** {@code ACTIVE} | {@code EXPIRED} | {@code ALL} (any casing); {@code null} means {@code ACTIVE}. */
    private String status;

    /** Free-text term matched case-insensitively across several columns. */
    private String search;

    /** Job type matched case-insensitively and ignoring separators. */
    private String jobType;
}
