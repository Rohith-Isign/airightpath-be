package com.rightpath.dto;

import java.time.Instant;
import java.time.ZoneOffset;

import com.rightpath.entity.InterviewMobileCapture;

/**
 * A still from the candidate's phone, as the review screen needs it.
 *
 * <p>No image bytes and no URL: the image sits behind the reviewer's own
 * permission and is fetched by id, exactly as the identity photo is.</p>
 */
public record MobileCaptureDto(
        Long id,
        Long interviewScheduleId,
        String kind,
        int frameIndex,
        Instant capturedAt) {

    public static MobileCaptureDto from(InterviewMobileCapture capture) {
        return new MobileCaptureDto(
                capture.getId(),
                capture.getInterviewScheduleId(),
                capture.getKind(),
                capture.getFrameIndex(),
                capture.getCapturedAt() == null ? null : capture.getCapturedAt().toInstant(ZoneOffset.UTC));
    }
}
