package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Which interview a phone's pairing token belongs to.
 *
 * <p>The phone has only the token from the QR code, and nothing on the server
 * connected that token to an interview — so a recording or photo arriving from
 * the phone could not be filed against anyone. The candidate's browser, signed
 * in and sitting a known interview, registers the token it just generated; from
 * then on the phone's uploads can be filed to that interview without the phone
 * ever holding a login.</p>
 *
 * <p>In the database rather than in memory so that it survives a restart and
 * is the same on every server instance.</p>
 */
@Entity
@Table(name = "interview_mobile_pairing", indexes = {
        @Index(name = "idx_mobile_pairing_interview", columnList = "interview_schedule_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewMobilePairing {

    @Id
    @Column(name = "token", length = 64)
    private String token;

    @Column(name = "interview_schedule_id", nullable = false)
    private Long interviewScheduleId;

    @Column(name = "candidate_email", nullable = false)
    private String candidateEmail;

    /** UTC. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * Set when the candidate started the interview without a phone. From then on
     * the token is refused everywhere, so a phone cannot be scanned in after the
     * interview has begun in single-camera mode and turn it into a dual-camera one
     * half way through. Nullable so rows from before this existed read as open.
     */
    @Column(name = "revoked")
    private Boolean revoked;

    /**
     * When the phone's room scan was saved. The candidate's page reads this to know the phone is verified,
     * so the Begin button does not depend on the live socket reaching it: the scan is on the server's record
     * whether or not any message got through. Nullable: rows from before this existed read as not verified.
     */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** Recording bytes accepted so far, so a token cannot be used to fill the bucket. */
    @Column(name = "bytes_received", nullable = false)
    @Builder.Default
    private long bytesReceived = 0L;
}
