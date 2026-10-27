package com.rightpath.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rightpath.enums.ExecutionStatus;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A failure explained well enough for a candidate to act on it.
 *
 * <p>{@code category} is what the UI should branch on; {@code exception},
 * {@code line} and {@code hint} are what it should show. {@code fullTrace} stays
 * available for the reviewer, but it is not what belongs on screen mid-exam.</p>
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeErrorInfo {

    /** Which kind of failure this is — the field to switch on. */
    private ExecutionStatus category;

    /** Legacy label kept for existing clients, e.g. "CompilationError", "RuntimeError". */
    private String type;

    /** The thrown type where there is one, e.g. "ArrayIndexOutOfBoundsException". */
    private String exception;

    /** Line in the candidate's source, where the toolchain reported one. */
    private Integer line;

    /** One-line summary, already stripped of temp paths. */
    private String message;

    /** A plain-language nudge toward the cause. Null when we have nothing useful to add. */
    private String hint;

    /** Raw compiler or runtime output, for the reviewer rather than the exam screen. */
    private String fullTrace;
}
