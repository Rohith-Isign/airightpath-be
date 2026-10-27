package com.rightpath.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rightpath.entity.ExamProctoringCapture;
import com.rightpath.enums.ProctoringCaptureType;

/**
 * A stored pre-exam capture as the admin console sees it.
 *
 * <p>The bytes are not inlined: {@code imageUrl} points at the endpoint that
 * streams them, so a listing of a full room scan stays small.</p>
 *
 * <p>The timestamps are {@link Instant}s, not {@code LocalDateTime}s, so the
 * JSON carries a {@code Z} and names the moment it refers to. Serialised
 * zone-less they were ambiguous, and the browser read a UTC wall-clock as local
 * time — a photo taken at 12:23 IST was shown as 06:53. The columns behind them
 * are zone-less and hold UTC by convention, which is what the conversion below
 * asserts.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProctoringCaptureDto(
        Long id,
        Long assessmentId,
        String candidateEmail,
        String jobPrefix,
        ProctoringCaptureType captureType,
        int frameIndex,
        String contentType,
        Long sizeBytes,
        Instant capturedAt,
        Instant uploadedAt,
        String imageUrl
) {

    /** Reads a zone-less stored timestamp as the UTC instant it represents. */
    private static Instant toInstant(LocalDateTime stored) {
        return stored == null ? null : stored.toInstant(ZoneOffset.UTC);
    }

    /**
     * Projects a stored capture, deriving the URL that serves its bytes.
     *
     * @param capture the persisted capture
     * @return the admin-facing view of it
     */
    public static ProctoringCaptureDto from(ExamProctoringCapture capture) {
        return new ProctoringCaptureDto(
                capture.getId(),
                capture.getAssessmentId(),
                capture.getCandidateEmail(),
                capture.getJobPrefix(),
                capture.getCaptureType(),
                capture.getFrameIndex(),
                capture.getContentType(),
                capture.getSizeBytes(),
                toInstant(capture.getCapturedAt()),
                toInstant(capture.getUploadedAt()),
                "/api/exam-proctoring/captures/" + capture.getId() + "/image");
    }
}
