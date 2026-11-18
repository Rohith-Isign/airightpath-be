package com.rightpath.dto.voice;

import com.rightpath.enums.TurnKind;

/**
 * One reply from the model, separated into what the candidate hears and what the
 * server acts on.
 *
 * @param spokenText   the reply with the control tags taken out, ready to store,
 *                     send and speak. {@code [CODING]} survives and leads, because
 *                     the browser opens the editor from it.
 * @param answerScore  the model's 0-10 rating of the answer it has just heard, or
 *                     null on the opening question and after a skip. This is the
 *                     only read on whether an answer was actually any good —
 *                     word count and vocal confidence measure fluency, and a
 *                     confident wrong answer scores well on both.
 * @param turnKind     whether this turn opens new ground, presses on the last
 *                     question, or re-asks it differently.
 * @param topic        the evaluation category this question belongs to, or null
 *                     when the model did not say. Drives coverage.
 * @param closing      whether the model asked to end the interview here.
 * @param level        BASIC, INTERMEDIATE, ADVANCED or EXPERT, or null when the model did not say (technical round only).
 * @param codeMismatch whether the model judged the code just submitted to solve a different problem than the one
 *                     asked, so it earns nothing for that question.
 */
public record ParsedInterviewReply(
        String spokenText,
        Integer answerScore,
        TurnKind turnKind,
        String topic,
        boolean closing,
        String level,
        boolean codeMismatch) {

    /** A reply that flags no mismatch: every caller that predates the flag. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing, String level) {
        this(spokenText, answerScore, turnKind, topic, closing, level, false);
    }

    /** A reply with no level: every caller that predates levels, and any model that does not give one. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing) {
        this(spokenText, answerScore, turnKind, topic, closing, null, false);
    }

    /** True when the model said which level this question is at. */
    public boolean hasLevel() {
        return level != null && !level.isBlank();
    }

    /** True when the model rated the answer it just heard. */
    public boolean hasScore() {
        return answerScore != null;
    }

    /** True when the model said which category the question belongs to. */
    public boolean hasTopic() {
        return topic != null && !topic.isBlank();
    }
}
