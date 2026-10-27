package com.rightpath.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.rightpath.dto.AssessmentContentDto;
import com.rightpath.dto.AssessmentUploadDto;
import com.rightpath.dto.AssignAssessmentBlobDto;
import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.dto.AssignmentReportDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.Result;

/**
 * Service interface for managing assessments, including uploading, assigning, submitting, 
 * evaluating, and retrieving assessment and result data.
 */
public interface AssessmentService {

    /**
     * Uploads an assessment to the system.
     *
     * @param dto The details of the assessment to be uploaded.
     * @return A message indicating the success or failure of the upload.
     * 
     * Developer Note: Log the assessment type, uploadedBy, and candidateEmail at INFO level.
     */
    String uploadAssessment(AssessmentUploadDto dto);

    /**
     * Assigns an assessment to a candidate with a job-specific prefix.
     *
     * <p>Assigning and notifying succeed or fail independently: the exam is
     * created locally, the email leaves through a rate-limited third party. A
     * candidate whose email could not be sent still has their assessment, and is
     * named in the returned report so the recruiter can resend rather than
     * discovering it when the candidate never turns up.</p>
     *
     * @param dto The assignment details including candidate and assessment information.
     * @param jobPrefix The prefix for the job role or position.
     * @return who was assigned, and who could not be told about it.
     *
     * Developer Note: Log assigned assessmentType, candidateEmail, and jobPrefix.
     */
    AssignmentReportDTO assignAssessment(AssignAssessmentDto dto, String jobPrefix);

    /**
     * Retrieves all assessments assigned to a specific candidate.
     *
     * @param candidateEmail The email of the candidate.
     * @return A list of assessments assigned to the candidate.
     * 
     * Developer Note: Log candidateEmail and the count of returned assessments.
     */
    List<Assessment> getCandidateAssessments(String candidateEmail);

    /**
     * Marks an assessment as submitted.
     *
     * @param id The ID of the assessment to be marked as submitted.
     * @return A message indicating the success or failure of the operation.
     * 
     * Developer Note: Log the assessmentId and submission timestamp.
     */
    String submitAssessment(Long id);

    /**
     * Records the result of a candidate's completed assessment.
     *
     * @param candidateEmail The email of the candidate.
     * @param assessmentType The type of assessment completed.
     * @param score The score obtained by the candidate.
     * @param jsonData The JSON-formatted breakdown or explanation of the score.
     * @param jobPrefix The prefix representing the job category.
     * @param assessmentId The assessment actually sat, or {@code null} to resolve it
     *        from the candidate's unattended assessments for this job. Pass it whenever
     *        it is known: a re-assigned exam leaves several rows of the same type, and
     *        only the id says which of them this result belongs to.
     * @param percentage The attempt as 0-100, as worked out by the exam page. This
     *        is what the paper's pass mark is compared against; {@code score} is
     *        raw marks and grading off it directly fails an 85% paper.
     * @param totalMarks What the paper was out of, so a percentage can still be
     *        derived when the client sends only marks.
     * @return A message indicating result recording status.
     *
     * Developer Note: Log candidateEmail, assessmentType, score, and jobPrefix.
     */
    String resultAssessment(String candidateEmail, String assessmentType, Double score, String jsonData,
            String jobPrefix, Long assessmentId, Double percentage, Double totalMarks);

    /**
     * Fetches the details of a specific assessment by its ID.
     *
     * @param assessmentId The ID of the assessment.
     * @return The Assessment entity if found.
     * 
     * Developer Note: Log assessmentId and the existence check result.
     */
    Assessment fetchAssessmentDetails(Long assessmentId);

    /**
     * Retrieves assessments for a candidate, with a toggle to fetch all or only active ones.
     *
     * @param candidateEmail The candidate's email.
     * @param fetchAll Whether to fetch all assessments or only active ones.
     * @return A list of assessments.
     * 
     * Developer Note: Log candidateEmail and fetchAll flag.
     */
    List<Assessment> getAssessments(String candidateEmail, boolean fetchAll);

    /**
     * Expires all assessments that have passed their deadline.
     * Updates assessment records to reflect expiration.
     * 
     * Developer Note: Log number of assessments expired and their IDs.
     */
    void expireAssessments();

    /**
     * Retrieves all results recorded for a candidate.
     *
     * @param email The candidate’s email.
     * @return A list of Result entities.
     * 
     * Developer Note: Log the email and result count.
     */
    List<Result> getResultsByEmail(String email);

    /**
     * Fetches a specific Result entity by its ID.
     *
     * @param id The result ID.
     * @return An Optional containing the result if found.
     * 
     * Developer Note: Log the result ID and if found or not.
     */
    Optional<Result> getResultById(Long id);

    /**
     * Retrieves the latest question paper for a candidate based on job prefix and assessment type.
     *
     * @param email The candidate’s email.
     * @param jobPrefix The job prefix.
     * @param assessmentType The type of the assessment.
     * @return A string (file name or download link).
     * 
     * Developer Note: Log the email, jobPrefix, and assessmentType.
     */
    String getLatestQuestionPaper(String email, String jobPrefix, String assessmentType);

    /**
     * Marks that a candidate has attended the exam.
     *
     * @param candidateEmail The email of the candidate.
     * @param assessmentId The ID of the attended assessment.
     * 
     * Developer Note: Log candidateEmail, assessmentId, and attendance timestamp.
     */
    /**
     * Records that a candidate has opened their paper, and says when they did.
     *
     * @return the instant the exam was first opened, stamped once and
     *         unchanged by later calls — so a reload reports attendance again
     *         without moving the clock
     */
    java.time.LocalDateTime markExamAsAttended(String candidateEmail, Long assessmentId);

    /**
     * Moves the exam window of a paper the candidate has not sat yet.
     *
     * @param id       the assessment to move
     * @param start    the new opening moment
     * @param deadline the new closing moment
     * @param notify   email the candidate their exam link with the new window
     * @return the updated assessment
     */
    Assessment rescheduleAssessment(Long id, java.time.LocalDateTime start, java.time.LocalDateTime deadline,
            boolean notify);

    String assignAssessmentToStorage(AssignAssessmentBlobDto dto);
    String downloadFileContentFromStorage(String containerName, String fileName);

	List<Map<String, Object>> getLatestAssessmentContent(String jobPrefix, String candidateEmail,
			String assessmentType);

	/**
	 * Fetches one assessment's paper together with the timing that governs it.
	 *
	 * @param assessmentId The ID of the assessment.
	 * @return The paper and the per-question allowance chosen when it was assigned.
	 */
	AssessmentContentDto getAssessmentContent(Long assessmentId);

	List<Result> getResultsByEmailAndJobPrefix(String email, String jobPrefix);
}
