package com.rightpath.dto;

/**
 * Lightweight carrier for a downloadable resume: the raw bytes plus the metadata
 * needed to render a correct HTTP download response (Content-Type and filename).
 *
 * <p>Backs {@code GET /api/view-resume/{email}}, which serves the resume the
 * candidate submitted with their job application
 * ({@code JobApplicationForCandidate.resumeData}) — the same column the ATS
 * engine reads — rather than the separate {@code resume} table.
 */
public record ResumeDownload(byte[] data, String fileName, String contentType) {
}
