package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.ConversationRole;
import com.rightpath.enums.TurnKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VoiceConversationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_schedule_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private CandidateInterviewSchedule interviewSchedule;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ConversationRole role;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private Integer wordCount;
    private Double wordsPerMinute;
    private Integer fillerWordCount;
    private Double confidenceScore;
    private Double speechDurationSeconds;

    @Column(columnDefinition = "TEXT")
    private String codeContent;

    @Column(length = 20)
    private String codeLanguage;

    /**
     * Stdout/stderr from the candidate's last run of {@link #codeContent}.
     *
     * <p>Stored beside the source so a reviewer reading the transcript later can
     * see whether the code ran, not just what was written — and so the evaluator
     * grades the same evidence a human interviewer would have had. Null where
     * the candidate never ran their code.</p>
     */
    @Column(columnDefinition = "TEXT")
    private String codeOutput;

    /**
     * The interviewer's 0-10 read on this answer, set on the CANDIDATE turn.
     *
     * <p>Written one turn late: the model rates an answer in the reply it gives
     * to it, so the score arrives with the next question and is put back onto
     * the turn it belongs to. Null on a skip, on a turn the model did not rate,
     * and on every INTERVIEWER row.</p>
     *
     * <p>This is the only measure of whether an answer was <em>right</em>.
     * Word count and vocal confidence are measures of fluency, and a candidate
     * who is wrong at length and with great poise scores well on both — which is
     * why difficulty is steered from this and not from those.</p>
     */
    private Integer answerScore;

    /**
     * The evaluation category an INTERVIEWER turn was asking about.
     *
     * <p>What makes coverage checkable: without it "have we asked about
     * databases yet" can only be answered by re-reading the transcript with
     * another model call. Null where the model did not name one.</p>
     */
    @Column(length = 80)
    private String topic;

    /** Whether this INTERVIEWER turn opened new ground, pressed, or re-asked. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private TurnKind turnKind;

    /**
     * Set on a CANDIDATE turn that tried to give the model instructions.
     *
     * <p>The content itself is left exactly as spoken — a reviewer needs to read
     * what was actually said, and it is evidence about the candidate. This only
     * marks it, so the attempt can be surfaced instead of sitting unremarked in
     * the middle of a long transcript.</p>
     */
    @Builder.Default
    private Boolean injectionSuspected = Boolean.FALSE;

    @Builder.Default
    private LocalDateTime timestamp = LocalDateTime.now();

}
