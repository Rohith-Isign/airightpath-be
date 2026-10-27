package com.rightpath.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ProctoringCaptureDto;
import com.rightpath.dto.ProctoringCaptureImage;

/**
 * Stores and serves the images captured on the exam instructions screen, before
 * the exam clock starts.
 *
 * <p>Captures are always recorded against a real assessment attempt owned by the
 * caller, so a reviewer looking at a disputed score can trust that the face in
 * the photo is the account that sat that exam.</p>
 */
public interface ExamProctoringService {

    /**
     * Stores the candidate's identity photo for an attempt, replacing any photo
     * already held for it.
     *
     * @param assessmentId   the assessment about to be attempted
     * @param candidateEmail the candidate named in the request
     * @param capturedAt     ISO-8601 client clock; ignored if unparseable
     * @param photo          the captured frame
     * @param actorEmail     the authenticated caller
     * @return the stored capture
     */
    ProctoringCaptureDto saveIdentityPhoto(String assessmentId, String candidateEmail, String capturedAt,
            MultipartFile photo, String actorEmail);

    /**
     * Stores an ordered room sweep for an attempt, replacing any previous sweep.
     * The frame count is whatever the client sent — it is configurable there.
     *
     * @param assessmentId   the assessment about to be attempted
     * @param candidateEmail the candidate named in the request
     * @param capturedAt     ISO-8601 client clock; ignored if unparseable
     * @param frames         the sweep frames, in order
     * @param actorEmail     the authenticated caller
     * @return the stored captures, in frame order
     */
    List<ProctoringCaptureDto> saveRoomScan(String assessmentId, String candidateEmail, String capturedAt,
            List<MultipartFile> frames, String actorEmail);

    /**
     * Everything captured for one attempt, for review alongside the result.
     *
     * @param assessmentId the assessment attempt
     * @return ordered captures; empty when nothing was captured
     */
    List<ProctoringCaptureDto> getCaptures(Long assessmentId);

    /**
     * Every capture across a candidate's attempts for one job.
     *
     * @param candidateEmail the candidate
     * @param jobPrefix      the job identifier prefix
     * @return ordered captures; empty when nothing was captured
     */
    List<ProctoringCaptureDto> getCaptures(String candidateEmail, String jobPrefix);

    /**
     * Pulls one capture's bytes back out of storage.
     *
     * @param captureId the capture to fetch
     * @return the image bytes and how to serve them
     */
    ProctoringCaptureImage loadImage(Long captureId);

    /**
     * Stores the identity photo taken before an interview.
     *
     * <p>Same evidence as the exam equivalent, filed against the interview
     * schedule instead of an assessment. The two id spaces are independent, so
     * they are deliberately separate parameters rather than one reused column.</p>
     *
     * @param scheduleId     the interview about to be sat
     * @param candidateEmail must match the authenticated candidate
     * @param actorEmail     the authenticated candidate, from the security context
     */
    ProctoringCaptureDto saveInterviewIdentityPhoto(String scheduleId, String candidateEmail, String capturedAt,
            org.springframework.web.multipart.MultipartFile photo, String actorEmail);

    /** Stores the room sweep taken before an interview, replacing any previous one. */
    java.util.List<ProctoringCaptureDto> saveInterviewRoomScan(String scheduleId, String candidateEmail,
            String capturedAt, java.util.List<org.springframework.web.multipart.MultipartFile> frames,
            String actorEmail);

    /** Every capture stored against one interview, for admin review. */
    java.util.List<ProctoringCaptureDto> getInterviewCaptures(Long scheduleId);
}
