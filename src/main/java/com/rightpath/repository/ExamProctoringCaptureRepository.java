package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.rightpath.entity.ExamProctoringCapture;
import com.rightpath.enums.ProctoringCaptureType;

/**
 * Lookup of the pre-exam images captured for an assessment attempt.
 */
@Repository
public interface ExamProctoringCaptureRepository extends JpaRepository<ExamProctoringCapture, Long> {

    /**
     * All captures for one attempt, identity photo first and room scan frames in
     * sweep order — the order a reviewer wants to page through them.
     *
     * @param assessmentId the assessment attempt
     * @return ordered captures (empty if the candidate captured nothing)
     */
    List<ExamProctoringCapture> findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(Long assessmentId);

    /**
     * The capture occupying one slot, used to replace a re-taken image in place
     * rather than accumulating rows.
     *
     * @param assessmentId the assessment attempt
     * @param captureType  identity photo or room scan frame
     * @param frameIndex   position within the capture (0 for an identity photo)
     * @return the existing capture, if any
     */
    Optional<ExamProctoringCapture> findByAssessmentIdAndCaptureTypeAndFrameIndex(
            Long assessmentId, ProctoringCaptureType captureType, int frameIndex);

    /**
     * Clears a previous capture of one kind so a re-run replaces it wholesale.
     * A repeated room scan can have a different frame count, so the old frames
     * cannot simply be overwritten one by one.
     *
     * @param assessmentId the assessment attempt
     * @param captureType  the kind of capture to clear
     */
    void deleteByAssessmentIdAndCaptureType(Long assessmentId, ProctoringCaptureType captureType);

    /** Interview equivalents of the three lookups above. */
    List<ExamProctoringCapture> findByInterviewScheduleIdOrderByCaptureTypeAscFrameIndexAsc(
            Long interviewScheduleId);

    Optional<ExamProctoringCapture> findByInterviewScheduleIdAndCaptureTypeAndFrameIndex(
            Long interviewScheduleId, ProctoringCaptureType captureType, int frameIndex);

    void deleteByInterviewScheduleIdAndCaptureType(Long interviewScheduleId, ProctoringCaptureType captureType);

    /**
     * Captures for every attempt of a candidate on one job, for the admin view
     * that opens from a result row.
     *
     * @param candidateEmail the candidate
     * @param jobPrefix      the job identifier prefix
     * @return ordered captures across the candidate's attempts for that job
     */
    List<ExamProctoringCapture> findByCandidateEmailAndJobPrefixOrderByAssessmentIdAscCaptureTypeAscFrameIndexAsc(
            String candidateEmail, String jobPrefix);
}
