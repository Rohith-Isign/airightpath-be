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
 * @param skill        the specific skill from the job (or a job-relevant resume skill) the question tests, such
 *                     as "Java" or "Spring Boot", or null when not named. Levels progress per skill.
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
        boolean codeMismatch,
        String skill,
        boolean notEnglish,
        String competency) {

    /** A reply that names no competency: every caller from before competencies were tagged. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing, String level, boolean codeMismatch, String skill, boolean notEnglish) {
        this(spokenText, answerScore, turnKind, topic, closing, level, codeMismatch, skill, notEnglish, null);
    }

    /** A reply that does not flag the answer as non-English: every caller from before the flag. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing, String level, boolean codeMismatch, String skill) {
        this(spokenText, answerScore, turnKind, topic, closing, level, codeMismatch, skill, false, null);
    }

    /** A reply that names no skill: every caller from before skills were tagged. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing, String level, boolean codeMismatch) {
        this(spokenText, answerScore, turnKind, topic, closing, level, codeMismatch, null, false, null);
    }

    /** A reply that flags no mismatch: every caller that predates the flag. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing, String level) {
        this(spokenText, answerScore, turnKind, topic, closing, level, false, null, false, null);
    }

    /** A reply with no level: every caller that predates levels, and any model that does not give one. */
    public ParsedInterviewReply(String spokenText, Integer answerScore, TurnKind turnKind, String topic,
            boolean closing) {
        this(spokenText, answerScore, turnKind, topic, closing, null, false, null, false, null);
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
