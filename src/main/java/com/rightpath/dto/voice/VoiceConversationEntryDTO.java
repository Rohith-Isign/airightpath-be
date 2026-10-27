package com.rightpath.dto.voice;

import java.time.LocalDateTime;

import com.rightpath.entity.VoiceConversationEntry;

/**
 * One turn of an interview, as the reviewer's transcript needs it.
 *
 * <p>The conversation endpoint used to return {@link VoiceConversationEntry}
 * itself. Its {@code interviewSchedule} is a lazy {@code @ManyToOne}, so
 * serialising the entity outside a transaction made Jackson touch an
 * uninitialised proxy and the request answered 500 — the transcript tab in the
 * interview results was empty for every interview ever held.</p>
 *
 * <p>A projection also settles what belongs in a transcript. The schedule is not
 * part of it: the caller asked for one schedule's conversation and already knows
 * which. Everything here is either what was said or a measurement of how it was
 * said, and {@code codeOutput} is included because the model was shown it when
 * scoring — a reviewer without it reads a different transcript than the one that
 * produced the score.</p>
 *
 * @param id                     this turn's id
 * @param interviewScheduleId    the interview it belongs to
 * @param role                   INTERVIEWER, CANDIDATE or SYSTEM
 * @param content                what was said, code and run output included for
 *                               candidate turns
 * @param wordCount              words spoken
 * @param wordsPerMinute         speaking pace
 * @param fillerWordCount        "um", "like", and the rest
 * @param confidenceScore        0-100, from tone analysis
 * @param speechDurationSeconds  how long they spoke
 * @param codeContent            code written for this answer, if any
 * @param codeLanguage           the language it was written in
 * @param codeOutput             what it printed when last run
 * @param answerScore            the interviewer's 0-10 read on a candidate turn
 * @param topic                  the category an interviewer turn was asking about
 * @param turnKind               NEW_QUESTION, FOLLOW_UP or REPHRASE
 * @param injectionSuspected     set on a candidate turn that tried to instruct the model
 * @param timestamp              when the turn was recorded
 */
public record VoiceConversationEntryDTO(
        Long id,
        Long interviewScheduleId,
        String role,
        String content,
        Integer wordCount,
        Double wordsPerMinute,
        Integer fillerWordCount,
        Double confidenceScore,
        Double speechDurationSeconds,
        String codeContent,
        String codeLanguage,
        String codeOutput,
        Integer answerScore,
        String topic,
        String turnKind,
        Boolean injectionSuspected,
        LocalDateTime timestamp) {

    /**
     * Maps one stored turn.
     *
     * <p>Reads the schedule id through {@code getId()} only, which a lazy proxy
     * answers without a database round trip — so this is safe to call outside a
     * transaction, which is the whole point of it.</p>
     */
    public static VoiceConversationEntryDTO from(VoiceConversationEntry entry) {
        return new VoiceConversationEntryDTO(
                entry.getId(),
                entry.getInterviewSchedule() != null ? entry.getInterviewSchedule().getId() : null,
                entry.getRole() != null ? entry.getRole().name() : null,
                entry.getContent(),
                entry.getWordCount(),
                entry.getWordsPerMinute(),
                entry.getFillerWordCount(),
                entry.getConfidenceScore(),
                entry.getSpeechDurationSeconds(),
                entry.getCodeContent(),
                entry.getCodeLanguage(),
                entry.getCodeOutput(),
                entry.getAnswerScore(),
                entry.getTopic(),
                entry.getTurnKind() != null ? entry.getTurnKind().name() : null,
                entry.getInjectionSuspected(),
                entry.getTimestamp());
    }
}
