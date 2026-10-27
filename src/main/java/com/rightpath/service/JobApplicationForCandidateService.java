package com.rightpath.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.rightpath.dto.JobApplicationForCandidateDTO;
import com.rightpath.dto.ScreeningResponseDTO;
import com.rightpath.entity.JobApplicationForCandidate;

@Service
public interface JobApplicationForCandidateService {

    /**
     * Applies for a job using the provided candidate DTO.
     *
     * @param jobApplicationForCandidateDTO The job application data submitted by the candidate.
     */
    void applyForJob(JobApplicationForCandidateDTO jobApplicationForCandidateDTO);

    /**
     * Retrieves all job applications.
     *
     * @return A list of all candidate job applications.
     */
    List<JobApplicationForCandidateDTO> getAllApplications();

    /**
     * Retrieves job applications by candidate email.
     *
     * @param email Candidate's email address.
     * @return A list of applications associated with the provided email.
     */
    List<JobApplicationForCandidateDTO> getApplicationsByEmail(String email);

    /**
     * Retrieves job applications by job prefix.
     *
     * @param jobPrefix Unique job prefix identifier.
     * @return A list of job applications related to the given job prefix.
     */
    List<JobApplicationForCandidateDTO> getApplicationsByJobPrefix(String jobPrefix);

    /**
     * Filters candidates for a specific job post ID.
     *
     * @param jobPostId The ID of the job post.
     * @return A list of filtered candidate applications.
     */
    List<JobApplicationForCandidateDTO> filterCandidates(Long jobPostId);

    /**
     * Screens every applicant on a job, persisting each shortlist decision.
     *
     * @param jobPrefix Unique identifier for the job.
     * @return A list of filtered candidate applications by job prefix.
     * @deprecated Prefer {@link #screenCandidates(String, List)}, which can screen a
     *             subset and reports per-candidate outcomes. This method screens the
     *             whole job on every call, which makes it unsafe to invoke from a read
     *             path; it remains only for the existing {@code GET /filterByPrefix} route.
     */
    @Deprecated(since = "2026-08")
    List<JobApplicationForCandidateDTO> filterCandidatesByPrefix(String jobPrefix);

    /**
     * Runs ATS screening over a chosen set of candidates, or the whole job.
     *
     * <p>Only applications still in the screening phase are re-evaluated: APPLIED
     * rows, and ATS-produced SHORTLISTED/REJECTED rows that have not been closed by a
     * finalised rejection. Anything further along the pipeline (ACKNOWLEDGED onward)
     * is reported as skipped and left untouched, so screening can never walk a
     * candidate backwards out of an exam or interview.</p>
     *
     * @param jobPrefix Unique identifier for the job.
     * @param emails    Candidates to screen; null or empty screens every applicant.
     * @return Per-candidate outcomes plus summary counts for the run.
     */
    ScreeningResponseDTO screenCandidates(String jobPrefix, List<String> emails);

    /**
     * Retrieves applicants by job post ID.
     *
     * @param jobPostId ID of the job post.
     * @return List of applicants.
     */
    List<JobApplicationForCandidateDTO> getApplicantsByJobPostId(Long jobPostId);

    /**
     * Retrieves shortlisted candidates by job prefix.
     *
     * @param jobPrefix Unique job prefix.
     * @return List of shortlisted candidates.
     */
    List<JobApplicationForCandidateDTO> getShortlistedCandidatesByPrefix(String jobPrefix);

    /**
     * Retrieves rejected candidates by job prefix.
     *
     * @param jobPrefix Unique job prefix.
     * @return List of rejected candidates.
     */
    List<JobApplicationForCandidateDTO> getRejectedCandidatesByPrefix(String jobPrefix);

    /**
     * Retrieves a job application entity by its unique ID.
     *
     * @param id Job application ID.
     * @return JobApplicationForCandidate entity.
     */
    JobApplicationForCandidate getById(Long id);

    /**
     * Sends an acknowledgment email to the candidate and updates the application status.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @param examSlot Scheduled exam date-time as business-zone wall-clock time,
     *                 already validated as present and not in the past by the caller.
     */
    void sendAcknowledgementMailAndUpdateStatus(String jobPrefix, String email, LocalDateTime examSlot);

    /**
     * Acknowledges a candidate manually and returns a response.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @return Status message.
     */
    String acknowledgeCandidate(String jobPrefix, String email);

    /**
     * Sends reconfirmation mail to a candidate.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     */
    void sendReconfirmationMail(String jobPrefix, String email);

    /**
     * Sends a rejection mail to the candidate.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     */
    void sendRejectionMail(String jobPrefix, String email);

    /**
     * Updates a candidate's job application using job prefix and email.
     *
     * @param dto DTO containing updated job application details.
     */
    void updateJobApplicationByJobPrefixAndEmail(JobApplicationForCandidateDTO dto);

    /**
     * Updates the referral verification status of a candidate's application.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @param referralStatus New status; must be a valid {@link com.rightpath.enums.ReferralStatus}
     *                       value (PENDING/VERIFIED/REJECTED), case-insensitive.
     * @return The updated application as a DTO.
     */
    JobApplicationForCandidateDTO updateReferralStatus(String jobPrefix, String email, String referralStatus);

    /**
     * Manually shortlists a single candidate without running ATS screening, honouring
     * the normal transition rules. Equivalent to
     * {@link #shortlistCandidateWithoutAts(String, String, boolean)} with no override.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     */
    void shortlistCandidateWithoutAts(String jobPrefix, String email);

    /**
     * Manually shortlists a single candidate without running ATS screening.
     *
     * <p>Only an APPLIED application can transition to SHORTLISTED; any other current
     * status throws, so the caller can report it as failed. On success the candidate
     * is notified (shortlist email + WhatsApp), best-effort.</p>
     *
     * <p>{@code override} is the one exception: it reopens a REJECTED application
     * ("Shortlist Anyway"), which the forward-only pipeline otherwise treats as
     * terminal. The override clears the finalised-rejection marker so the row returns
     * to the screening phase and can be re-screened later, and is logged with the
     * acting recruiter. It has no effect on any other status — a SELECTED or
     * mid-pipeline application still throws.</p>
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @param override Whether to reopen a REJECTED application.
     */
    void shortlistCandidateWithoutAts(String jobPrefix, String email, boolean override);

    /**
     * Updates the written test status for aptitude and programming tests.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @param isAptitudePassed Aptitude test pass flag.
     * @param isProgrammingPassed Programming test pass flag.
     */
    void updateWrittenTestStatus(String jobPrefix, String email, boolean isAptitudePassed, boolean isProgrammingPassed);

    /**
     * Sends a failure mail to the candidate.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     */
    void sendFailureMail(String jobPrefix, String email);

    /**
     * Sends a success mail to the candidate.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     */
    void sendSuccessMail(String jobPrefix, String email);

	JobApplicationForCandidate scheduleInterview(String jobPrefix, String email);

    /**
     * Sends an exam link to the candidate and updates examLinkStatus.
     *
     * @param jobPrefix Job identifier.
     * @param email Candidate's email.
     * @param examSlot Scheduled start as business-zone wall-clock time, already
     *                 validated as not in the past by the caller; {@code null}
     *                 starts the exam window now.
     */
    void sendExamLink(String jobPrefix, String email, LocalDateTime examSlot);

}
