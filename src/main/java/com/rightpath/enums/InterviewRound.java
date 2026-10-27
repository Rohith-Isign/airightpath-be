package com.rightpath.enums;

/**
 * Which interview a candidate is sitting.
 *
 * <p>The AI interview was a single session walking six {@link InterviewPhase}
 * values, so "technical" and "behavioural" were stretches of one conversation
 * rather than separate events. A round is the event: it is scheduled on its own,
 * has its own deadline, its own prompt and its own result.</p>
 *
 * <p>Phases still exist inside a round — the model decides its own ordering
 * within the script it is given — so the two are not alternatives. A round says
 * which interview; a phase says where you are inside one.</p>
 */
public enum InterviewRound {

    L2_TECHNICAL("L2 — Technical", PromptType.INTERVIEW_L2_TECHNICAL),

    L3_BEHAVIORAL("L3 — Behavioural", PromptType.INTERVIEW_L3_BEHAVIORAL);

    /**
     * The round a schedule belongs to when it does not say.
     *
     * <p>Every interview booked before rounds existed is a technical one: that
     * was the only interview there was. Rows with a null round therefore read as
     * L2, which keeps historic results where reviewers expect them instead of
     * silently reclassifying them.</p>
     */
    public static final InterviewRound DEFAULT = L2_TECHNICAL;

    private final String displayName;
    private final PromptType promptType;

    InterviewRound(String displayName, PromptType promptType) {
        this.displayName = displayName;
        this.promptType = promptType;
    }

    /** Label for screens and emails. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * The prompt type holding this round's instructions.
     *
     * <p>Callers should fall back to {@link PromptType#INTERVIEW} when no prompt
     * of this type is configured, so a job set up before rounds existed still
     * interviews with the prompt it has.</p>
     */
    public PromptType getPromptType() {
        return promptType;
    }

    /** Null-safe read, for schedules written before the column existed. */
    public static InterviewRound orDefault(InterviewRound round) {
        return round == null ? DEFAULT : round;
    }
}
