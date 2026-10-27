package com.rightpath.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ApiResponse;
import com.rightpath.dto.ProctoringCaptureDto;
import com.rightpath.dto.ProctoringCaptureImage;
import com.rightpath.service.ExamProctoringService;

/**
 * Pre-exam proctoring captures, taken on the instructions screen before the exam
 * clock starts.
 *
 * <p>The uploads are candidate-facing and the reads are reviewer-facing: a
 * candidate may only file a capture against their own attempt, and only a user
 * with {@code ASSESSMENT_READ} can look at what was captured.</p>
 */
@RestController
@RequestMapping("/api/exam-proctoring")
public class ExamProctoringController {

    private final ExamProctoringService examProctoringService;

    public ExamProctoringController(ExamProctoringService examProctoringService) {
        this.examProctoringService = examProctoringService;
    }

    /**
     * Stores the identity photo taken before the candidate starts an assessment.
     * Re-taking it replaces the photo already held for that attempt.
     *
     * @param assessmentId   the assessment about to be attempted
     * @param candidateEmail the logged-in candidate; must match the caller
     * @param capturedAt     ISO-8601 client clock (optional)
     * @param photo          the captured JPEG
     * @param authentication the authenticated caller
     * @return the stored capture in the standard response envelope
     */
    @PostMapping(value = "/identity-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProctoringCaptureDto>> uploadIdentityPhoto(
            @RequestParam("assessmentId") String assessmentId,
            @RequestParam("candidateEmail") String candidateEmail,
            @RequestParam(value = "capturedAt", required = false) String capturedAt,
            @RequestPart("photo") MultipartFile photo,
            Authentication authentication) {

        ProctoringCaptureDto stored = examProctoringService.saveIdentityPhoto(assessmentId, candidateEmail, capturedAt,
                photo, authentication.getName());

        return ResponseEntity.ok(ApiResponse.ok(stored));
    }

    /**
     * Stores the optional guided room sweep. The frame count is configured on the
     * client, so any number of {@code frames} parts is accepted up to the
     * server-side ceiling.
     *
     * @param assessmentId   the assessment about to be attempted
     * @param candidateEmail the logged-in candidate; must match the caller
     * @param capturedAt     ISO-8601 client clock (optional)
     * @param frames         the sweep frames, in order
     * @param authentication the authenticated caller
     * @return the stored captures in the standard response envelope
     */
    @PostMapping(value = "/room-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<ProctoringCaptureDto>>> uploadRoomScan(
            @RequestParam("assessmentId") String assessmentId,
            @RequestParam("candidateEmail") String candidateEmail,
            @RequestParam(value = "capturedAt", required = false) String capturedAt,
            @RequestPart("frames") List<MultipartFile> frames,
            Authentication authentication) {

        List<ProctoringCaptureDto> stored = examProctoringService.saveRoomScan(assessmentId, candidateEmail, capturedAt,
                frames, authentication.getName());

        return ResponseEntity.ok(ApiResponse.ok(stored));
    }

    /**
     * Everything captured for one assessment attempt, for review next to the
     * assessment result.
     *
     * @param assessmentId the assessment attempt
     * @return ordered captures; an empty list when nothing was captured
     */
    /**
     * Stores the identity photo taken before an interview.
     *
     * <p>Interview twin of {@code /identity-photo}. A separate path rather than
     * an extra parameter on that one: assessment ids and interview-schedule ids
     * are independent sequences, and a single endpoint taking "an id" would make
     * it possible to file an interview capture under an assessment.</p>
     */
    @PostMapping(value = "/interview/identity-photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProctoringCaptureDto>> uploadInterviewIdentityPhoto(
            @RequestParam("scheduleId") String scheduleId,
            @RequestParam("candidateEmail") String candidateEmail,
            @RequestParam(value = "capturedAt", required = false) String capturedAt,
            @RequestPart("photo") MultipartFile photo,
            Authentication authentication) {

        ProctoringCaptureDto stored = examProctoringService.saveInterviewIdentityPhoto(scheduleId, candidateEmail,
                capturedAt, photo, authentication.getName());

        return ResponseEntity.ok(ApiResponse.ok(stored));
    }

    /** Stores the room sweep taken before an interview, replacing any previous one. */
    @PostMapping(value = "/interview/room-scan", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<List<ProctoringCaptureDto>>> uploadInterviewRoomScan(
            @RequestParam("scheduleId") String scheduleId,
            @RequestParam("candidateEmail") String candidateEmail,
            @RequestParam(value = "capturedAt", required = false) String capturedAt,
            @RequestPart("frames") List<MultipartFile> frames,
            Authentication authentication) {

        List<ProctoringCaptureDto> stored = examProctoringService.saveInterviewRoomScan(scheduleId, candidateEmail,
                capturedAt, frames, authentication.getName());

        return ResponseEntity.ok(ApiResponse.ok(stored));
    }

    /** Every capture stored against one interview, for admin review. */
    @GetMapping("/interviews/{scheduleId}/captures")
    public ResponseEntity<ApiResponse<List<ProctoringCaptureDto>>> listInterviewCaptures(
            @PathVariable Long scheduleId) {
        return ResponseEntity.ok(ApiResponse.ok(examProctoringService.getInterviewCaptures(scheduleId)));
    }

    @GetMapping("/assessments/{assessmentId}/captures")
    @PreAuthorize("hasAuthority('ASSESSMENT_READ')")
    public ResponseEntity<ApiResponse<List<ProctoringCaptureDto>>> getCapturesForAssessment(
            @PathVariable Long assessmentId) {
        return ResponseEntity.ok(ApiResponse.ok(examProctoringService.getCaptures(assessmentId)));
    }

    /**
     * Every capture across a candidate's attempts for one job, for the review that
     * opens from a results table.
     *
     * @param candidateEmail the candidate
     * @param jobPrefix      the job identifier prefix
     * @return ordered captures; an empty list when nothing was captured
     */
    @GetMapping("/captures")
    @PreAuthorize("hasAuthority('ASSESSMENT_READ')")
    public ResponseEntity<ApiResponse<List<ProctoringCaptureDto>>> getCapturesForCandidate(
            @RequestParam String candidateEmail,
            @RequestParam String jobPrefix) {
        return ResponseEntity.ok(ApiResponse.ok(examProctoringService.getCaptures(candidateEmail, jobPrefix)));
    }

    /**
     * Streams the bytes of one capture.
     *
     * @param captureId the capture to serve
     * @return the image, typed as it was uploaded
     */
    @GetMapping("/captures/{captureId}/image")
    @PreAuthorize("hasAuthority('ASSESSMENT_READ')")
    public ResponseEntity<byte[]> getCaptureImage(@PathVariable Long captureId) {
        ProctoringCaptureImage image = examProctoringService.loadImage(captureId);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.fileName() + "\"")
                .body(image.bytes());
    }
}
