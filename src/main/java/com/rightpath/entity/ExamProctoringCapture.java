package com.rightpath.entity;

import java.time.LocalDateTime;

import com.rightpath.enums.ProctoringCaptureType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single image captured before an exam starts, held for later review.
 *
 * <p>The bytes live in object storage; this row is the pointer plus the context a
 * reviewer needs when a score is disputed — which attempt it belongs to, who was
 * logged in, and when the client says it was taken.</p>
 *
 * <p>The unique constraint is what enforces "one identity photo per attempt":
 * an identity photo always occupies frame index 0, so a re-capture replaces the
 * previous one instead of accumulating. Room scan frames occupy 0..n-1.</p>
 */
@Entity
@Table(
        name = "exam_proctoring_capture",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_proctoring_capture_slot",
                        columnNames = {"assessment_id", "capture_type", "frame_index"}),
                // Interviews need their own slot constraint. MySQL treats NULLs
                // as distinct in a unique index, so interview rows — which leave
                // assessment_id null — are not constrained by the one above at
                // all, and would accumulate a new identity photo per re-capture.
                @UniqueConstraint(
                        name = "uk_proctoring_capture_interview_slot",
                        columnNames = {"interview_schedule_id", "capture_type", "frame_index"})
        },
        indexes = {
                @Index(name = "idx_proctoring_capture_assessment", columnList = "assessment_id"),
                @Index(name = "idx_proctoring_capture_interview", columnList = "interview_schedule_id"),
                @Index(name = "idx_proctoring_capture_candidate", columnList = "candidate_email")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamProctoringCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The assessment attempt this capture belongs to, or null for an interview.
     *
     * <p>Nullable since interviews began capturing too: exactly one of this and
     * {@link #interviewScheduleId} is set. They are separate id sequences, so
     * storing a schedule id here would make assessment 42 and interview 42
     * indistinguishable — and a reviewer could be shown the wrong candidate's
     * photo.</p>
     */
    @Column(name = "assessment_id")
    private Long assessmentId;

    /** The interview schedule this capture belongs to, or null for an exam. */
    @Column(name = "interview_schedule_id")
    private Long interviewScheduleId;

    /** The candidate who was logged in at capture time. */
    @Column(name = "candidate_email", nullable = false)
    private String candidateEmail;

    /** Copied off the assessment so the admin listing needs no join. */
    @Column(name = "job_prefix")
    private String jobPrefix;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_type", nullable = false, length = 32)
    private ProctoringCaptureType captureType;

    /** Position within an ordered capture; always 0 for an identity photo. */
    @Column(name = "frame_index", nullable = false)
    private int frameIndex;

    /** Storage prefix (bucket folder) holding the image. */
    @Column(name = "container_name", length = 100)
    private String containerName;

    /** Storage key within the prefix. */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Client clock, as reported by the browser; informational only. */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    /** Server clock at the moment the upload was accepted. */
    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
}
