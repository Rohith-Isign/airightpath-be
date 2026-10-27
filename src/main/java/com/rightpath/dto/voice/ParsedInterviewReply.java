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
 */
public record ParsedInterviewReply(
        String spokenText,
        Integer answerScore,
        TurnKind turnKind,
        String topic,
        boolean closing) {

    /** True when the model rated the answer it just heard. */
    public boolean hasScore() {
        return answerScore != null;
    }

    /** True when the model said which category the question belongs to. */
    public boolean hasTopic() {
        return topic != null && !topic.isBlank();
    }
}
