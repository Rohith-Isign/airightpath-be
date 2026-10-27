package com.rightpath.util;

/**
 * Renders a candidate's answer for the model when that answer includes code.
 *
 * <p>One definition, used by both the live voice path and the question-generation
 * path. They had grown separate copies of this formatting, which meant the text
 * the interviewer saw while choosing its next question could differ from the text
 * the evaluator later graded — the same answer, described two ways.</p>
 *
 * <p>The output block is always present for a coding answer, never omitted when
 * empty. "The candidate did not run this code" is a real signal about how someone
 * works, and without stating it the model cannot distinguish never-run code from
 * code that ran cleanly and printed nothing.</p>
 */
public final class CodingAnswerFormatter {

    private static final String NOT_RUN = "The candidate did not run this code.";

    private CodingAnswerFormatter() {
    }

    /**
     * @param transcript   what the candidate said; returned unchanged when there is no code
     * @param codeContent  the submitted source, or null/blank if this was not a coding answer
     * @param codeLanguage language the source was written in; falls back to "text"
     * @param codeOutput   stdout/stderr from their last run, or null if they never ran it
     * @return the answer as the model should read it
     */
    public static String render(String transcript, String codeContent, String codeLanguage, String codeOutput) {
        String spoken = transcript == null ? "" : transcript;
        if (isBlank(codeContent)) {
            return spoken;
        }

        String language = isBlank(codeLanguage) ? "text" : codeLanguage;
        return spoken
                + "\n\n[CODE (" + language + ")]\n" + codeContent
                + "\n\n[RUN OUTPUT]\n" + (isBlank(codeOutput) ? NOT_RUN : codeOutput);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
