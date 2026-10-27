package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * How a coding answer is described to the model.
 *
 * <p>This text is read twice — once by the interviewer choosing its next question
 * and once by the evaluator grading the round — so the interesting property is
 * that both get the same thing, and that "never ran it" is stated rather than
 * left as an absence the model has to infer.</p>
 */
class CodingAnswerFormatterTest {

    @Test
    void aSpokenAnswerWithNoCodeIsUnchanged() {
        assertEquals("I would use a hash map.",
                CodingAnswerFormatter.render("I would use a hash map.", null, "java", null));
        assertEquals("I would use a hash map.",
                CodingAnswerFormatter.render("I would use a hash map.", "   ", "java", "ignored"));
    }

    @Test
    void codeAndItsOutputBothAppear() {
        String rendered = CodingAnswerFormatter.render(
                "Here is my solution.", "print(1)", "python", "1");

        assertTrue(rendered.contains("Here is my solution."));
        assertTrue(rendered.contains("[CODE (python)]"));
        assertTrue(rendered.contains("print(1)"));
        assertTrue(rendered.contains("[RUN OUTPUT]"));
        assertTrue(rendered.contains("\n1"));
    }

    @Test
    void notRunningTheCodeIsStatedExplicitly() {
        // The whole point: an empty output block would read as "ran and printed
        // nothing", which is a different fact about the candidate.
        String rendered = CodingAnswerFormatter.render("Done.", "print(1)", "python", null);

        assertTrue(rendered.contains("[RUN OUTPUT]"));
        assertTrue(rendered.contains("did not run this code"));
    }

    @Test
    void blankOutputCountsAsNotRun() {
        String rendered = CodingAnswerFormatter.render("Done.", "print(1)", "python", "   ");
        assertTrue(rendered.contains("did not run this code"));
    }

    @Test
    void aCompilerErrorIsPassedThroughAsOutput() {
        // A failed build is evidence, not an error to swallow.
        String rendered = CodingAnswerFormatter.render(
                "I think this works.", "int x = ;", "java", "error: illegal start of expression");

        assertTrue(rendered.contains("error: illegal start of expression"));
        assertFalse(rendered.contains("did not run this code"));
    }

    @Test
    void unknownLanguageFallsBackRatherThanPrintingNull() {
        String rendered = CodingAnswerFormatter.render("x", "code", null, "out");
        assertTrue(rendered.contains("[CODE (text)]"));
        assertFalse(rendered.contains("null"));
    }

    @Test
    void aNullTranscriptStillRendersTheCode() {
        // Code submitted with no spoken answer must not become the string "null".
        String rendered = CodingAnswerFormatter.render(null, "print(1)", "python", "1");
        assertFalse(rendered.contains("null"));
        assertTrue(rendered.startsWith("\n\n[CODE"));
    }
}
