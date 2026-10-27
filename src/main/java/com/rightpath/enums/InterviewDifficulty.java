package com.rightpath.enums;

/**
 * How hard an interview is pitched before the candidate says anything.
 *
 * <p>The adaptive adjustment moves around this, it does not replace it. A
 * graduate scheme and a senior opening can both run a candidate who is
 * answering well, and "harder than they were getting" means two very different
 * questions in those two interviews. Without a baseline the model inferred the
 * level from the job prompt's tone, which made it a property of how the
 * recruiter happened to write their prompt.</p>
 */
public enum InterviewDifficulty {

    FOUNDATIONAL("""
            Pitch this interview at the fundamentals. Ask about core concepts and everyday usage rather than \
            internals, and prefer questions with a concrete right answer over open-ended design. Assume little or no \
            commercial experience."""),

    STANDARD("""
            Pitch this interview at a competent working level. Expect the candidate to explain not just what they \
            would do but why, and to have met the common pitfalls in their area. Some design questions are fair, \
            but keep them small."""),

    ADVANCED("""
            Pitch this interview high. Expect depth, trade-offs, failure modes and questions of scale, and expect \
            the candidate to reason about approaches they have not used. Do not spend turns on definitions.""");

    /** The default where a job has not configured one: what every interview did before. */
    public static final InterviewDifficulty DEFAULT = STANDARD;

    private final String directive;

    InterviewDifficulty(String directive) {
        this.directive = directive;
    }

    /** The instruction handed to the model. */
    public String getDirective() {
        return directive;
    }

    /** Null-safe read, for templates and rows written before this existed. */
    public static InterviewDifficulty orDefault(InterviewDifficulty difficulty) {
        return difficulty == null ? DEFAULT : difficulty;
    }
}
