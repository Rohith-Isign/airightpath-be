package com.rightpath.enums;

/**
 * What an interviewer turn was for.
 *
 * <p>Until now every reply from the model was simply "the next question", and
 * the interview could only move forward: an answer that stopped half way, or one
 * that showed the candidate had misread the question, got the same treatment as
 * a complete one. A turn now says which of the three it is, so the server can
 * hold the model to a budget for each and so a reviewer can see where the
 * conversation doubled back.</p>
 */
public enum TurnKind {

    /** Moves to new ground. The only kind that spends a question from the budget. */
    NEW_QUESTION,

    /** Presses on the same question because the answer was incomplete. */
    FOLLOW_UP,

    /** Asks the same concept a different way, because the answer missed the point. */
    REPHRASE;

    /** Whether this turn opened a new subject, rather than staying on the last one. */
    public boolean isNewQuestion() {
        return this == NEW_QUESTION;
    }
}
