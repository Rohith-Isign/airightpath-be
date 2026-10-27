package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One recording that is being uploaded to storage as the interview runs.
 *
 * <p>A recording is now a single continuous file: the candidate's browser sends
 * it to storage piece by piece while the interview is in progress, and joins it
 * when the interview ends. Until then the file does not exist as far as anyone
 * reading the interview is concerned — only the pieces do. If the candidate's
 * browser dies before the join, the recording would sit in storage unreadable
 * for ever. This row is how the server knows such an upload exists, so it can
 * finish it on the candidate's behalf (see {@code RecordingUploadSweeper}).</p>
 *
 * <p>A plain string status rather than an enum, for the same reason the phone
 * captures use one: {@code ddl-auto: update} will not extend an existing
 * database ENUM.</p>
 */
@Entity
@Table(name = "interview_recording_upload", indexes = {
        @Index(name = "idx_rec_upload_status", columnList = "status, last_activity_at"),
        @Index(name = "idx_rec_upload_upload_id", columnList = "upload_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewRecordingUpload {

    public static final String OPEN = "OPEN";
    /** Claimed by one request that is joining the pieces, so two cannot at once. */
    public static final String COMPLETING = "COMPLETING";
    public static final String COMPLETED = "COMPLETED";
    public static final String ABORTED = "ABORTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_schedule_id", nullable = false)
    private Long interviewScheduleId;

    /** camera, screen or mobile. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "blob_name", nullable = false, length = 512)
    private String blobName;

    @Column(name = "upload_id", nullable = false, length = 512)
    private String uploadId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    /** UTC. */
    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    /** UTC. Moves on every piece, so an upload that went quiet can be told from one still running. */
    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "parts_received", nullable = false)
    @Builder.Default
    private int partsReceived = 0;

    /** Where the joined recording ended up, once it has. */
    @Column(name = "stored_reference", length = 1024)
    private String storedReference;
}
