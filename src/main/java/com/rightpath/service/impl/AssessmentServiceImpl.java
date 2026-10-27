package com.rightpath.service.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.dto.AssessmentContentDto;
import com.rightpath.dto.AssessmentUploadDto;
import com.rightpath.dto.AssignAssessmentBlobDto;
import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.dto.AssignmentReportDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.Result;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.enums.AssessmentType;
import com.rightpath.enums.ResultStatus;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.ResultRepository;
import com.rightpath.service.AssessmentService;
import com.rightpath.service.EmailService;
import com.rightpath.service.StorageService;
import com.rightpath.util.StatusTransitionValidator;

import jakarta.mail.MessagingException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.transaction.Transactional;

@Transactional
@Service
public class AssessmentServiceImpl implements AssessmentService {

	@Value("${aws.s3.prefix.exam:exam}")
	private String examPrefix;

	private static final Logger logger = LoggerFactory.getLogger(AssessmentServiceImpl.class);


	@Autowired
	private final AssessmentRepository assessmentRepository;
	private final StorageService storageService;

	@Autowired
	private com.rightpath.service.QuestionBankTracker questionBankTracker;

	private JobApplicationForCandidateRepository jobApplicationRepository;

	@Autowired
	private final ResultRepository resultRepository;
	@Autowired
	private final EmailService emailService;

	public AssessmentServiceImpl(AssessmentRepository assessmentRepository, ResultRepository resultRepository,
			EmailService emailService, JobApplicationForCandidateRepository jobApplicationRepository,
			StorageService storageService) {
		super();
		this.assessmentRepository = assessmentRepository;
		this.resultRepository = resultRepository;
		this.emailService = emailService;
		this.jobApplicationRepository = jobApplicationRepository;
		this.storageService = storageService;
	}

	/**
	 * Uploads a new assessment to the system.
	 *
	 * @param dto The DTO containing assessment details and files.
	 * @return A success message.
	 */
	@Override
	public String uploadAssessment(AssessmentUploadDto dto) {
		logger.info("Uploading assessment: {}", dto);
		try {
			MultipartFile questionPaperFile = dto.getQuestionPaper();

			Assessment assessment = new Assessment();
			assessment.setAssessmentType(AssessmentType.valueOf(dto.getAssessmentType()));
			assessment.setCandidateEmail(dto.getCandidateEmail());
			assessment.setUploadedBy(dto.getUploadedBy());
			assessment.setDeadline(dto.getDeadline());
			assessment.setAdminAcceptance(dto.isAdminAcceptance());
			assessment.setAdminComments(dto.getAdminComments());
			assessment.setQuestionPaper(new String(questionPaperFile.getBytes(), StandardCharsets.UTF_8));

			if (dto.getAnswerKey() != null && !dto.getAnswerKey().isEmpty()) {
				assessment.setAnswerKey(dto.getAnswerKey().getBytes());
			}

			assessmentRepository.save(assessment);
			logger.info("Assessment uploaded successfully for candidate: {}", dto.getCandidateEmail());
			return "Assessment uploaded successfully.";
		} catch (IOException e) {
			logger.error("Error uploading assessment for candidate: {}", dto.getCandidateEmail(), e);
			throw new RuntimeException("Error uploading files", e);
		}
	}

	/**
	 * Retrieves active assessments assigned to a specific candidate.
	 *
	 * @param candidateEmail The email of the candidate.
	 * @return A list of active assessments.
	 */
	@Override
	public List<Assessment> getCandidateAssessments(String candidateEmail) {
	    List<Assessment> activeAssessments =
	            assessmentRepository.findActiveAssessments(candidateEmail);

	    logger.info("Fetched {} active assessments for candidate: {}",
	            activeAssessments.size(), candidateEmail);

	    return activeAssessments; // return empty list if none
	}

	/**
	 * Fetches a specific assessment's details using its ID.
	 *
	 * @param assessmentId The ID of the assessment.
	 * @return The assessment entity.
	 */
	@Override
	public Assessment fetchAssessmentDetails(Long assessmentId) {
		Assessment assessment = assessmentRepository.findById(assessmentId)
				.orElseThrow(() -> new ResourceNotFoundException("Assessment not found"));
		resolveQuestionPaperFromStorage(assessment);
		return assessment;
	}

	/**
	 * Records the result of an assessment for a candidate.
	 *
	 * @param candidateEmail The candidate's email.
	 * @param assessmentType The type of the assessment.
	 * @param score          The score achieved by the candidate.
	 * @param jsonData       The detailed result in JSON format.
	 * @return A success message.
	 */
	@Override
	public String resultAssessment(String candidateEmail, String assessmentType, Double score, String jsonData,
			String jobPrefix, Long assessmentId, Double percentage, Double totalMarks) {
		logger.info("Recording result for candidate: {}, type: {}, jobPrefix: {}, assessmentId: {}", candidateEmail,
				assessmentType, jobPrefix, assessmentId);

		AssessmentType type = AssessmentType.valueOf(assessmentType);
		Assessment attempt = resolveSubmittedAttempt(candidateEmail, jobPrefix, type, assessmentId);

		if (attempt != null) {
			attempt.setExamAttended(true);
			assessmentRepository.save(attempt);
			logger.info("Marked assessment {} as attended for candidate: {}", attempt.getId(), candidateEmail);

			// Check if all assessments for this candidate+job are completed
			if (jobPrefix != null) {
				long pending = assessmentRepository.countByCandidateEmailAndJobPrefixAndExamAttendedFalse(
						candidateEmail, jobPrefix);
				if (pending == 0) {
					List<JobApplicationForCandidate> apps = jobApplicationRepository
							.findByJobPrefixAndEmail(jobPrefix, candidateEmail);
					for (JobApplicationForCandidate app : apps) {
						if (app.getStatus() == ApplicationStatus.EXAM_SENT) {
							StatusTransitionValidator.validate(app.getStatus(), ApplicationStatus.EXAM_COMPLETED);
							app.setStatus(ApplicationStatus.EXAM_COMPLETED);
							jobApplicationRepository.save(app);
						}
					}
				}
			}
		} else {
			logger.warn("No unattended {} assessment found for {} on job {} — result stored unlinked.",
					assessmentType, candidateEmail, jobPrefix);
		}

		Result result = new Result();
		result.setCandidateEmail(candidateEmail);
		result.setAssessmentType(type);
		result.setScore(score);
		result.setTotalMarks(totalMarks);
		result.setPercentage(resolvePercentage(percentage, score, totalMarks));
		result.setStatus(gradeAgainstPassMark(result.getPercentage(), attempt));
		result.setSubmittedAt(LocalDateTime.now());
		result.setResultsJson(jsonData);
		result.setJobPrefix(jobPrefix);
		// Without this the assessment_id column stayed null on every row, so a
		// result could not be traced back to the attempt that produced it — which
		// is the whole difference between two attempts of a re-sent exam.
		result.setAssessment(attempt);

		resultRepository.save(result);

		logger.info("Assessment result saved successfully for candidate: {}", candidateEmail);
		return "Assessment submitted successfully.";
	}

	/**
	 * The pass mark to store, clamped to a sane 1-100 and defaulted when absent.
	 *
	 * Zero is rejected along with the negatives: a paper nobody can fail is far
	 * more likely a blank field or a bad parse than a deliberate choice.
	 */
	private Integer passMarkOrDefault(Integer requested) {
		if (requested == null || requested <= 0 || requested > 100) {
			return Assessment.DEFAULT_PASS_PERCENTAGE;
		}
		return requested;
	}

	/**
	 * The attempt as a percentage, preferring the figure the exam page worked out.
	 *
	 * The exam knows the paper it just marked, so its percentage is authoritative.
	 * Falling back to marks over total covers clients that send only those. When
	 * neither is available the result stores no percentage rather than a guess —
	 * treating raw marks as a percentage is precisely the bug this replaces, where
	 * 17 marks out of 20 was compared against a pass mark of 50 and failed.
	 */
	private Double resolvePercentage(Double percentage, Double score, Double totalMarks) {
		if (percentage != null && !percentage.isNaN()) {
			return Math.min(100d, Math.max(0d, percentage));
		}
		if (score != null && totalMarks != null && totalMarks > 0) {
			return Math.min(100d, Math.max(0d, (score / totalMarks) * 100d));
		}
		return null;
	}

	/**
	 * PASSED when the attempt reaches the pass mark configured for that paper.
	 *
	 * An unknown percentage cannot be graded, and calling it FAILED would brand a
	 * candidate on missing data — so it is recorded as FAILED only when there is a
	 * percentage to justify it, and left null otherwise for the reviewer to judge.
	 */
	private ResultStatus gradeAgainstPassMark(Double percentage, Assessment attempt) {
		if (percentage == null) {
			return null;
		}
		int passMark = attempt == null ? Assessment.DEFAULT_PASS_PERCENTAGE : attempt.effectivePassPercentage();
		return percentage >= passMark ? ResultStatus.PASSED : ResultStatus.FAILED;
	}

	/**
	 * Works out which assessment row a submission belongs to.
	 *
	 * <p>The id sent by the exam page is authoritative — it is the paper the
	 * candidate actually had open. The lookup is only a fallback for clients that
	 * do not send one, and it deliberately considers unattended rows for this job
	 * alone: the previous code scanned every assessment ever assigned to the
	 * email, across all jobs, and took the first of a matching type.</p>
	 *
	 * @return the attempt being submitted, or {@code null} if none can be resolved
	 */
	private Assessment resolveSubmittedAttempt(String candidateEmail, String jobPrefix, AssessmentType type,
			Long assessmentId) {
		if (assessmentId != null) {
			Assessment byId = assessmentRepository.findById(assessmentId).orElse(null);
			// Guard the id: a mismatched owner or type means the caller is wrong
			// about which attempt this is, and silently trusting it would attach
			// the result to another candidate's paper.
			if (byId != null && candidateEmail.equalsIgnoreCase(byId.getCandidateEmail())
					&& byId.getAssessmentType() == type) {
				return byId;
			}
			logger.warn("Assessment id {} does not match candidate {} and type {} — falling back to lookup.",
					assessmentId, candidateEmail, type);
		}

		if (jobPrefix == null) {
			return null;
		}

		return assessmentRepository
				.findTopByCandidateEmailAndJobPrefixAndAssessmentTypeAndExamAttendedFalseOrderByAssignedAtAsc(
						candidateEmail, jobPrefix, type)
				.orElse(null);
	}

	/**
	 * Submits an assessment by marking it as attended and sends a confirmation
	 * email.
	 *
	 * @param id The ID of the assessment.
	 * @return A success message.
	 */
	@Override
	public String submitAssessment(Long id) {
		logger.info("Submitting assessment with ID: {}", id);

		Assessment assessment = assessmentRepository.findById(id)
				.orElseThrow(() -> new EntityNotFoundException("Assessment not found for ID: " + id));

		if (assessment.isExamAttended()) {
			logger.warn("Assessment already submitted for ID: {}", id);
			throw new IllegalStateException("Assessment has already been submitted.");
		}

		assessment.setExamAttended(true);
		assessmentRepository.save(assessment);

		String candidateEmail = assessment.getCandidateEmail();
		String jobPrefix = assessment.getJobPrefix();

		if (candidateEmail != null && !candidateEmail.isEmpty() && jobPrefix != null) {
			// Update job application status
			List<JobApplicationForCandidate> applications = jobApplicationRepository.findByJobPrefixAndEmail(jobPrefix,
					candidateEmail);
			// Counted once: it cannot change between application rows, and asking per
			// row ran the same query for every one of them.
			long pending = assessmentRepository
					.countByCandidateEmailAndJobPrefixAndExamAttendedFalse(candidateEmail, jobPrefix);

			for (JobApplicationForCandidate application : applications) {
				application.setExamCompletedStatus(examCompletedStatusFor(assessment, pending));

				if (pending == 0 && application.getStatus() == ApplicationStatus.EXAM_SENT) {
					StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.EXAM_COMPLETED);
					application.setStatus(ApplicationStatus.EXAM_COMPLETED);
				}

				jobApplicationRepository.save(application);
			}

			sendSubmissionEmail(assessment);
			logger.info("Email notification sent and job application status updated for: {}", candidateEmail);
		} else {
			logger.warn("Candidate email or jobPrefix not available for assessment ID: {}", id);
		}

		return "Assessment submitted successfully.";
	}

	/**
	 * Moves a paper's exam window, and tells the candidate where it moved to.
	 *
	 * <p>Only a paper that has not been sat can move. Once a candidate has opened
	 * and submitted it, the window it was sat under is part of the record: changing
	 * it cannot give them any more time and would leave the result describing a
	 * sitting that never happened.</p>
	 *
	 * <p>A window whose deadline has come round again is no longer expired — the
	 * flag is cleared here rather than waiting for the sweep to notice, so the
	 * candidate can sit the paper as soon as the recruiter has moved it.</p>
	 *
	 * @param id       the assessment to move
	 * @param start    the new opening moment
	 * @param deadline the new closing moment
	 * @param notify   send the candidate their exam link with the new window
	 * @return the updated assessment
	 */
	@Override
	public Assessment rescheduleAssessment(Long id, LocalDateTime start, LocalDateTime deadline, boolean notify) {
		Assessment assessment = assessmentRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Assessment not found for ID: " + id));
	
		if (assessment.isExamAttended()) {
			throw new IllegalStateException(
					"This paper has already been sat, so its exam window can no longer be changed.");
		}
		if (start == null || deadline == null || !start.isBefore(deadline)) {
			throw new IllegalArgumentException("The exam window must start before it ends.");
		}
	
		assessment.setStartTime(start);
		assessment.setDeadline(deadline);
		assessment.setExpired(deadline.isBefore(LocalDateTime.now()));
		assessmentRepository.save(assessment);
	
		logger.info("Rescheduled assessment {} for {} to {} - {}", id, assessment.getCandidateEmail(), start,
				deadline);
	
		if (notify) {
			// Best effort, like the assignment mail: the window has moved either way,
			// and a mail server having a bad day must not roll that back.
			try {
				emailService.sendExamLink(assessment.getCandidateEmail(), start, deadline, assessment.getJobPrefix());
			} catch (Exception e) {
				logger.warn("Rescheduled assessment {} but could not email {}: {}", id,
						assessment.getCandidateEmail(), e.getMessage());
			}
		}
	
		return assessment;
	}
	
	/**
	 * Tells the candidate their paper was received, naming the test they actually
	 * sat.
	 *
	 * Chosen from the assessment's own type rather than from the endpoint that was
	 * called. The two mails differ only in which test they name, and having one
	 * route hardcode each meant the aptitude paper — which the exam page reports
	 * through the same route as coding — told every candidate their coding test was
	 * in, for a test many of them had not been set.
	 */
	private void sendSubmissionEmail(Assessment assessment) {
		if (assessment.getAssessmentType() == AssessmentType.CODING) {
			emailService.sendSuccessCodingExamAttend(assessment.getCandidateEmail(), assessment.getJobPrefix());
		} else {
			emailService.sendSuccessExamAttend(assessment.getCandidateEmail(), assessment.getJobPrefix());
		}
	}

	/**
	 * What the application row should say now this paper is in.
	 *
	 * Names the module while anything is still outstanding, so a candidate who has
	 * handed in aptitude and still owes coding does not read as finished, and says
	 * the exam is complete only once nothing is pending. Both routes wrote a fixed
	 * string before — one always "Aptitude Completed", the other always "Exam
	 * Completed" — so the column described the endpoint rather than the candidate.
	 *
	 * @param pending assessments still unattended for this candidate and job
	 */
	private String examCompletedStatusFor(Assessment assessment, long pending) {
		if (pending == 0) {
			return "Exam Completed";
		}
		return assessment.getAssessmentType() == AssessmentType.CODING ? "Coding Completed" : "Aptitude Completed";
	}

	/**
	 * Retrieves assessments for a given candidate or all assessments if requested.
	 *
	 * @param candidateEmail The candidate's email.
	 * @param fetchAll       Flag to indicate if all records should be fetched.
	 * @return A list of assessments.
	 */
	@Override
	public List<Assessment> getAssessments(String candidateEmail, boolean fetchAll) {
		if (fetchAll) {
			logger.info("Fetching all assessments.");
			return assessmentRepository.findAll();
		} else {
			logger.info("Fetching assessments for candidate: {}", candidateEmail);
			return assessmentRepository.findByCandidateEmail(candidateEmail);
		}
	}

	/**
	 * Expires all assessments that have passed their deadline.
	 */
	@Override
	public void expireAssessments() {
		logger.info("Expiring overdue assessments...");
		List<Assessment> expiredAssessments = assessmentRepository.findExpiredAssessments();
		for (Assessment assessment : expiredAssessments) {
			assessment.setExpired(true);
			assessmentRepository.save(assessment);
			logger.debug("Expired assessment ID: {}", assessment.getId());
		}
	}

	private static String paperText(org.springframework.web.multipart.MultipartFile file) {
		try {
			return file == null || file.isEmpty() ? null : new String(file.getBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Record which questions this candidate was given.
	 *
	 * <p>Bookkeeping, and deliberately never allowed to fail the assignment: the candidate has been
	 * given their exam, and a problem writing the question log is something to see in the logs, not a
	 * reason to roll the exam back.</p>
	 */
	private void trackQuestions(String jobPrefix, AssessmentType type, String paper, Assessment assessment) {
		if (paper == null) {
			return;
		}
		try {
			questionBankTracker.recordAssignment(jobPrefix, type, paper, assessment.getId(),
					assessment.getCandidateEmail(), assessment.getAssignedAt(), assessment.getUploadedBy());
		} catch (Exception e) {
			logger.error("Could not record the {} questions given to {} for {} in the question bank", type,
					assessment.getCandidateEmail(), jobPrefix, e);
		}
	}

	/**
	 * Assigns an assessment to multiple candidates and emails them their exam link.
	 *
	 * <p>The email is a notification, not part of the assignment. It goes out
	 * through a third-party SMTP host that rate-limits, and letting that failure
	 * escape aborted the whole request: the transaction rolled back every
	 * candidate — including the ones already emailed — while the question papers
	 * uploaded to storage stayed behind, and the recruiter got a 500 naming
	 * nobody. A candidate who cannot be emailed now keeps their assessment and is
	 * named in the report so the recruiter can resend.</p>
	 *
	 * @param dto The DTO containing assessment assignment details.
	 * @return who was assigned, and who could not be told about it.
	 */
	@Override
	public AssignmentReportDTO assignAssessment(AssignAssessmentDto dto, String jobPrefix) {
		AssignmentReportDTO report = new AssignmentReportDTO();
		// Read once: it is the same paper for every candidate, and what is recorded is what each was given.
		String aptitudePaper = paperText(dto.getAptitudeQuestionPaper());
		String codingPaper = paperText(dto.getCodingQuestionPaper());
		for (String email : dto.getCandidateEmails()) {
			// Process Aptitude Assessment
			if (dto.getAptitudeQuestionPaper() != null && !dto.getAptitudeQuestionPaper().isEmpty()) {
				Assessment aptitudeAssessment = new Assessment();
				aptitudeAssessment.setAssessmentType(AssessmentType.APTITUDE);
				aptitudeAssessment.setCandidateEmail(email);
				aptitudeAssessment.setUploadedBy(dto.getUploadedBy());
				String aptFileName = jobPrefix + "_aptitude_" + email.replace("@", "_") + "_" + System.currentTimeMillis() + ".json";
				storageService.uploadFile(examPrefix, aptFileName, dto.getAptitudeQuestionPaper());
				aptitudeAssessment.setContainerName(examPrefix);
				aptitudeAssessment.setFileName(aptFileName);
				aptitudeAssessment.setAssignedAt(LocalDateTime.now());
				aptitudeAssessment.setStartTime(dto.getStartTime());
				aptitudeAssessment.setDeadline(dto.getDeadline());
				aptitudeAssessment.setJobPrefix(jobPrefix);
				aptitudeAssessment.setAdminAcceptance(dto.isAdminAcceptance());
				aptitudeAssessment.setAdminComments(dto.getAdminComments());
				aptitudeAssessment.setMinutesPerQuestion(dto.getAptitudeMinutesPerQuestion());
				aptitudeAssessment.setQuestionCount(dto.getAptitudeQuestionCount());
				aptitudeAssessment.setEstimatedDurationMinutes(dto.getAptitudeEstimatedDurationMinutes());
				aptitudeAssessment.setPassPercentage(passMarkOrDefault(dto.getAptitudePassPercentage()));

				if (dto.getAptitudeAnswerKey() != null) {
					try {
						aptitudeAssessment.setAnswerKey(dto.getAptitudeAnswerKey().getBytes());
					} catch (IOException e) {
						throw new RuntimeException("Error reading aptitude answer key", e);
					}
				}
				assessmentRepository.save(aptitudeAssessment);
				trackQuestions(jobPrefix, AssessmentType.APTITUDE, aptitudePaper, aptitudeAssessment);
			}

			// Process Coding Assessment (without answer key)
			if (dto.getCodingQuestionPaper() != null && !dto.getCodingQuestionPaper().isEmpty()) {
				Assessment codingAssessment = new Assessment();
				codingAssessment.setAssessmentType(AssessmentType.CODING);
				codingAssessment.setCandidateEmail(email);
				codingAssessment.setUploadedBy(dto.getUploadedBy());
				String codingFileName = jobPrefix + "_coding_" + email.replace("@", "_") + "_" + System.currentTimeMillis() + ".json";
				storageService.uploadFile(examPrefix, codingFileName, dto.getCodingQuestionPaper());
				codingAssessment.setContainerName(examPrefix);
				codingAssessment.setFileName(codingFileName);
				codingAssessment.setAssignedAt(LocalDateTime.now());
				codingAssessment.setStartTime(dto.getStartTime());
				codingAssessment.setDeadline(dto.getDeadline());
				codingAssessment.setJobPrefix(jobPrefix);
				codingAssessment.setAdminAcceptance(dto.isAdminAcceptance());
				codingAssessment.setAdminComments(dto.getAdminComments());
				codingAssessment.setMinutesPerQuestion(dto.getCodingMinutesPerQuestion());
				codingAssessment.setQuestionCount(dto.getCodingQuestionCount());
				codingAssessment.setEstimatedDurationMinutes(dto.getCodingEstimatedDurationMinutes());
				codingAssessment.setPassPercentage(passMarkOrDefault(dto.getCodingPassPercentage()));
				assessmentRepository.save(codingAssessment);
				trackQuestions(jobPrefix, AssessmentType.CODING, codingPaper, codingAssessment);
			}

			// Notify, then record the result. EXAM_SENT is only set once the mail has
			// actually gone: the column reads "Exam Link Sent", and a candidate marked
			// as told is one nobody will think to chase.
			try {
				emailService.sendExamLink(email, dto.getStartTime(), dto.getDeadline(), jobPrefix);
				updateApplicationStatus(jobPrefix, email);
				report.recordNotified(email);
			} catch (RuntimeException e) {
				// Only delivery failures are tolerated. Spring throws MailException
				// directly and EmailServiceImpl wraps MessagingException in a plain
				// RuntimeException, so both shapes arrive here — but anything else is a
				// real fault and must not be filed as "the email didn't go out".
				if (!isMailFailure(e)) {
					throw e;
				}
				logger.warn("Assessment assigned to {} on job {}, but the exam-link email failed: {}",
						email, jobPrefix, rootMessage(e));
				report.recordNotNotified(email, rootMessage(e));
			}
		}
		return report;
	}

	/** True when this failure came out of the mail stack rather than our own code. */
	private static boolean isMailFailure(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause()) {
			if (cause instanceof MailException || cause instanceof MessagingException) {
				return true;
			}
			if (cause == cause.getCause()) {
				break;
			}
		}
		return false;
	}

	/** The innermost message, which is the one naming what the mail host said. */
	private static String rootMessage(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause.getMessage();
	}

	private void updateApplicationStatus(String jobPrefix, String email) {
		List<JobApplicationForCandidate> applications = jobApplicationRepository.findByJobPrefixAndEmail(jobPrefix,
				email);
		for (JobApplicationForCandidate application : applications) {
			advanceToExamSent(application, jobPrefix, email);
		}
	}

	/**
	 * Moves an application to EXAM_SENT, shortlisting it first when the exam is
	 * being sent straight out of APPLIED.
	 *
	 * <p>Assigning an exam to an applicant is itself the decision to shortlist
	 * them, so the recruiter should not have to record that separately. The step
	 * is taken explicitly rather than by allowing APPLIED to jump the queue: the
	 * candidate genuinely passes through SHORTLISTED, the shortlist column says
	 * how they got there, and the pipeline counts stay honest.</p>
	 */
	private void advanceToExamSent(JobApplicationForCandidate application, String jobPrefix, String email) {
		if (application.getStatus() == ApplicationStatus.APPLIED) {
			StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.SHORTLISTED);
			application.setStatus(ApplicationStatus.SHORTLISTED);
			application.setShortlistStatus("Shortlisted (Direct Exam)");
			logger.info("Direct exam assignment shortlisted {} on job {} on the way to EXAM_SENT", email, jobPrefix);
		}

		StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.EXAM_SENT);
		application.setStatus(ApplicationStatus.EXAM_SENT);
		application.setExamLinkStatus("Exam Link Sent");
		jobApplicationRepository.save(application);
	}

	/**
	 * Scheduled task that updates expired assessments every minute.
	 */
	@Scheduled(cron = "0 * * * * *")
	@Transactional
	public void updateExpiredAssessments() {
		int updatedCount = assessmentRepository.updateExpiredAssessments();
		logger.info("Updated expired assessments count: {}", updatedCount);
	}

	/**
	 * Retrieves results for a specific candidate using their email.
	 *
	 * @param email The candidate's email address.
	 * @return A list of results.
	 */
	@Override
	public List<Result> getResultsByEmail(String email) {
		if (email == null || email.isEmpty()) {
			throw new IllegalArgumentException("Email cannot be null or empty");
		}
		logger.info("Fetching results for candidate: {}", email);
		return resultRepository.findByCandidateEmail(email);
	}

	/**
	 * Fetches a specific result record by its ID.
	 *
	 * @param id The result ID.
	 * @return An Optional containing the result if found.
	 */
	@Override
	public Optional<Result> getResultById(Long id) {
		logger.info("Fetching result with ID: {}", id);
		return resultRepository.findById(id);

	}

	public LocalDateTime markExamAsAttended(String email, Long assessmentId) {
		Optional<Assessment> assessmentOpt = assessmentRepository.findById(assessmentId);
		if (assessmentOpt.isEmpty()) {
			throw new ResourceNotFoundException("Assessment not found with ID: " + assessmentId);
		}

		Assessment assessment = assessmentOpt.get();
		assessment.setExamAttended(true);
		// Stamped once. The exam page reports the paper attended as it opens, and a
		// reload part-way through reports it again — overwriting would turn a
		// candidate who refreshed at the 90-minute mark into one who had just begun.
		if (assessment.getExamStartedAt() == null) {
			assessment.setExamStartedAt(LocalDateTime.now(ZoneOffset.UTC));
		}
		assessmentRepository.save(assessment);

		List<JobApplicationForCandidate> applications = jobApplicationRepository
				.findByJobPrefixAndEmail(assessment.getJobPrefix(), email);

		// Checked before the list is read, not after: taking element 0 first threw
		// IndexOutOfBounds on exactly the case this guard exists to report.
		if (applications.isEmpty()) {
			throw new ResourceNotFoundException("No job application found for the given jobPrefix and email.");
		}

		long pending = assessmentRepository
				.countByCandidateEmailAndJobPrefixAndExamAttendedFalse(email, assessment.getJobPrefix());

		for (JobApplicationForCandidate app : applications) {
			app.setExamCompletedStatus(examCompletedStatusFor(assessment, pending));
			jobApplicationRepository.save(app);
		}

		// Sent once, not once per application row — a candidate holding two rows for
		// the same job was mailed twice for a single submission.
		sendSubmissionEmail(assessment);

		// The stamped start, so the paper's clock can count down to a fixed
		// moment instead of starting a fresh countdown on every load.
		return assessment.getExamStartedAt();
	}

	@Override
	public String getLatestQuestionPaper(String email, String jobPrefix, String assessmentType) {

		Assessment assessment = assessmentRepository
				.findTopByCandidateEmailAndJobPrefixAndAssessmentTypeOrderByAssignedAtDesc(email, jobPrefix,
						AssessmentType.valueOf(assessmentType))
				.orElseThrow(() -> new IllegalStateException("No assessment found for the given details."));

		resolveQuestionPaperFromStorage(assessment);
		return assessment.getQuestionPaper();
	}

	@Override
	public String assignAssessmentToStorage(AssignAssessmentBlobDto dto) {
		try {
			for (String email : dto.getCandidateEmails()) {
				String fileUrl = storageService.uploadFile(examPrefix, dto.getFileName(), dto.getFile());
				Assessment assessment = new Assessment();
				assessment.setAssessmentType(AssessmentType.valueOf(dto.getAssessmentType()));
				assessment.setCandidateEmail(email);
				assessment.setUploadedBy(dto.getUploadedBy());
				assessment.setAssignedAt(LocalDateTime.now());
				assessment.setStartTime(dto.getStartTime());
				assessment.setDeadline(dto.getDeadline());
				assessment.setJobPrefix(dto.getJobPrefix());
				assessment.setContainerName(examPrefix);
				assessment.setFileName(dto.getFileName());
				assessmentRepository.save(assessment);
				emailService.sendExamLink(email, dto.getStartTime(), dto.getDeadline(), dto.getJobPrefix());
				updateApplicationStatus(dto.getJobPrefix(), email);
			}
			return "Assessments assigned successfully to storage.";
		} catch (Exception e) {
			throw new RuntimeException("Error uploading file to storage", e);
		}
	}

	@Override
	public String downloadFileContentFromStorage(String containerName, String fileName) {
		byte[] fileBytes = storageService.downloadFile(containerName, fileName);
		return new String(fileBytes, StandardCharsets.UTF_8);
	}

	private void resolveQuestionPaperFromStorage(Assessment assessment) {
		if (assessment.getQuestionPaper() == null
				&& assessment.getContainerName() != null
				&& assessment.getFileName() != null) {
			String content = downloadFileContentFromStorage(
					assessment.getContainerName(), assessment.getFileName());
			assessment.setQuestionPaper(content);
		}
	}

	public List<Map<String, Object>> getLatestAssessmentContent(String jobPrefix, String candidateEmail,
			String assessmentType) {

		Assessment assessment = assessmentRepository
				.findTopByJobPrefixAndCandidateEmailAndAssessmentTypeOrderByAssignedAtDesc(jobPrefix, candidateEmail,
						AssessmentType.valueOf(assessmentType))
				.orElseThrow(() -> new ResourceNotFoundException("No assessment found for given inputs: " + candidateEmail + ", "
						+ jobPrefix + ", " + assessmentType));

		String fileContent = downloadFileContentFromStorage(assessment.getContainerName(), assessment.getFileName());

		try {
			ObjectMapper objectMapper = new ObjectMapper();
			return objectMapper.readValue(fileContent, new TypeReference<List<Map<String, Object>>>() {
			});
		} catch (IOException e) {
			throw new RuntimeException("Failed to parse assessment JSON from storage", e);
		}
	}

	@Override
	public AssessmentContentDto getAssessmentContent(Long assessmentId) {
		Assessment assessment = assessmentRepository.findById(assessmentId)
				.orElseThrow(() -> new ResourceNotFoundException("Assessment not found with id: " + assessmentId));

		// Read the paper into a local rather than onto the entity: this method runs in
		// a transaction, so assigning it back would flush the whole paper into the
		// questionPaper column on every read.
		String fileContent = assessment.getQuestionPaper();
		if (fileContent == null && assessment.getContainerName() != null && assessment.getFileName() != null) {
			fileContent = downloadFileContentFromStorage(assessment.getContainerName(), assessment.getFileName());
		}
		if (fileContent == null) {
			throw new ResourceNotFoundException("No question paper stored for assessment id: " + assessmentId);
		}

		List<Map<String, Object>> questions;
		try {
			questions = new ObjectMapper().readValue(fileContent, new TypeReference<List<Map<String, Object>>>() {
			});
		} catch (IOException e) {
			throw new RuntimeException("Failed to parse assessment JSON for assessment id: " + assessmentId, e);
		}

		return new AssessmentContentDto(
				assessment.getId(),
				assessment.getAssessmentType(),
				assessment.getJobPrefix(),
				assessment.getCandidateEmail(),
				assessment.getStartTime(),
				assessment.getDeadline(),
				assessment.isExamAttended(),
				assessment.getMinutesPerQuestion(),
				assessment.getDurationMinutes(),
				questions);
	}

	@Override
	public List<Result> getResultsByEmailAndJobPrefix(String email, String jobPrefix) {
		if (email == null || jobPrefix == null) {
			throw new IllegalArgumentException("Email and jobPrefix must not be null");
		}
		return resultRepository.findByCandidateEmailAndJobPrefix(email, jobPrefix);
	}

}
