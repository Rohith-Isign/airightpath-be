package com.rightpath.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.dto.AssessmentContentDto;
import com.rightpath.dto.AssessmentUploadDto;
import com.rightpath.dto.AssignAssessmentBlobDto;
import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.dto.AssessmentSummaryDTO;
import com.rightpath.dto.AssignmentReportDTO;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.Result;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.ResultRepository;
import com.rightpath.service.AssessmentService;

import jakarta.persistence.EntityNotFoundException;

/**
 * Controller class to handle endpoints related to Assessments.
 */
@RestController
@RequestMapping("/api")
public class AssessmentController {

	/**
	 * Bounds on the per-question allowance. The exam clock is this multiplied by
	 * the question count, so a typo here would either give a candidate no time at
	 * all or an exam that outlives its own deadline.
	 */
	private static final int MIN_MINUTES_PER_QUESTION = 1;
	private static final int MAX_MINUTES_PER_QUESTION = 600;

	@Autowired
	private final AssessmentService assessmentService;
	@Autowired
	private AssessmentRepository assessmentRepository;
	

	@Autowired
	private ResultRepository resultRepository;

	/**
	 * Constructor-based dependency injection for AssessmentService.
	 *
	 * @param assessmentService AssessmentService instance
	 */
	 public AssessmentController(AssessmentService assessmentService) {
			this.assessmentService = assessmentService;
		}

	/**
	 * Endpoint to upload an assessment. Accessible by users with ROLE_ADMIN.
	 *
	 * @param dto AssessmentUploadDto containing assessment details
	 * @return ResponseEntity with status and message
	 */
	@PostMapping("/upload")
	@PreAuthorize("hasAuthority('ASSESSMENT_UPLOAD')")
	public ResponseEntity<Map<String, String>> uploadAssessment(@ModelAttribute AssessmentUploadDto dto) {
		Map<String, String> response = new HashMap<>();
		assessmentService.uploadAssessment(dto);
		response.put("status", "success");
		response.put("message", "Assessment uploaded successfully.");
		return ResponseEntity.ok(response); // Return JSON response
	}

	/**
	 * Endpoint to assign an assessment. Accessible by users with ROLE_ADMIN.
	 *
	 * @param dto AssignAssessmentDto containing assignment details
	 * @return ResponseEntity with status and message
	 */
	@PostMapping("/assign")
	@PreAuthorize("hasAuthority('ASSESSMENT_ASSIGN')")
    public ResponseEntity<Map<String, Object>> assignAssessment(
        @RequestPart("candidateEmails") String candidateEmails,
        @RequestPart("startTime") String startTime,
        @RequestPart("deadline") String deadline,
        @RequestPart(value = "aptitudeQuestionPaper", required = false) MultipartFile aptitudeQuestionPaper,
        @RequestPart(value = "codingQuestionPaper", required = false) MultipartFile codingQuestionPaper,
        @RequestPart(value = "aptitudeAnswerKey", required = false) MultipartFile aptitudeAnswerKey,
        @RequestPart("uploadedBy") String uploadedBy,
        @RequestPart("jobPrefix") String jobPrefix,
        @RequestParam(value = "aptitudeMinutesPerQuestion", required = false) String aptitudeMinutesPerQuestion,
        @RequestParam(value = "aptitudeQuestionCount", required = false) String aptitudeQuestionCount,
        @RequestParam(value = "aptitudeEstimatedDurationMinutes", required = false) String aptitudeEstimatedDurationMinutes,
        @RequestParam(value = "codingMinutesPerQuestion", required = false) String codingMinutesPerQuestion,
        @RequestParam(value = "codingQuestionCount", required = false) String codingQuestionCount,
        @RequestParam(value = "codingEstimatedDurationMinutes", required = false) String codingEstimatedDurationMinutes
    ) {
        Map<String, Object> response = new HashMap<>();

        // Parse datetime strings
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        LocalDateTime parsedStartTime = LocalDateTime.parse(startTime, formatter);
        LocalDateTime parsedDeadline = LocalDateTime.parse(deadline, formatter);

        // Validate time range
        if (parsedStartTime.isAfter(parsedDeadline)) {
            response.put("status", "error");
            response.put("message", "Start time must be before deadline");
            return ResponseEntity.badRequest().body(response);
        }

        // Build DTO
        AssignAssessmentDto dto = new AssignAssessmentDto();
        dto.setCandidateEmails(List.of(candidateEmails.split(",")));
        dto.setStartTime(parsedStartTime);
        dto.setDeadline(parsedDeadline);
        dto.setAptitudeQuestionPaper(aptitudeQuestionPaper);
        dto.setCodingQuestionPaper(codingQuestionPaper);
        dto.setAptitudeAnswerKey(aptitudeAnswerKey);
        dto.setUploadedBy(uploadedBy);
        dto.setJobPrefix(jobPrefix);

        // Exam timing. Absent values leave the columns null, which the client reads
        // as "use the default for this assessment type" — the behaviour before
        // per-question timing existed.
        try {
            dto.setAptitudeMinutesPerQuestion(
                    parseMinutesPerQuestion(aptitudeMinutesPerQuestion, "aptitudeMinutesPerQuestion"));
            dto.setAptitudeQuestionCount(parseCount(aptitudeQuestionCount, "aptitudeQuestionCount"));
            dto.setAptitudeEstimatedDurationMinutes(
                    parseCount(aptitudeEstimatedDurationMinutes, "aptitudeEstimatedDurationMinutes"));
            dto.setCodingMinutesPerQuestion(
                    parseMinutesPerQuestion(codingMinutesPerQuestion, "codingMinutesPerQuestion"));
            dto.setCodingQuestionCount(parseCount(codingQuestionCount, "codingQuestionCount"));
            dto.setCodingEstimatedDurationMinutes(
                    parseCount(codingEstimatedDurationMinutes, "codingEstimatedDurationMinutes"));
        } catch (IllegalArgumentException e) {
            response.put("status", "error");
            response.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }

        // Call service
        AssignmentReportDTO report = assessmentService.assignAssessment(dto, jobPrefix);

        response.put("status", report.allNotified() ? "success" : "partial");
        response.put("message", report.getMessage());
        response.put("assigned", report.getAssigned());
        response.put("notified", report.getNotified());
        response.put("notNotified", report.getNotNotified());
        response.put("assignedCount", report.getAssignedCount());
        response.put("notifiedCount", report.getNotifiedCount());
        response.put("notNotifiedCount", report.getNotNotifiedCount());

        // 207 when the exams exist but some candidates were not emailed. A plain 200
        // would report a mail outage as an unqualified success; the old 500 threw the
        // whole assignment away over one rate-limited message.
        return report.allNotified()
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.MULTI_STATUS).body(response);
    }

	/**
	 * Reads a per-question allowance, which drives the exam clock and so has to be
	 * a sane positive number.
	 *
	 * @param raw   the submitted value, possibly absent
	 * @param field the field name, for the error message
	 * @return the parsed allowance, or null when not submitted
	 */
	private static Integer parseMinutesPerQuestion(String raw, String field) {
		Integer value = parseOptionalInt(raw, field);
		if (value != null && (value < MIN_MINUTES_PER_QUESTION || value > MAX_MINUTES_PER_QUESTION)) {
			throw new IllegalArgumentException(field + " must be between " + MIN_MINUTES_PER_QUESTION + " and "
					+ MAX_MINUTES_PER_QUESTION + " minutes, received: " + value);
		}
		return value;
	}

	/**
	 * Reads one of the informational counts. These never shorten an exam, so they
	 * only have to be non-negative.
	 *
	 * @param raw   the submitted value, possibly absent
	 * @param field the field name, for the error message
	 * @return the parsed count, or null when not submitted
	 */
	private static Integer parseCount(String raw, String field) {
		Integer value = parseOptionalInt(raw, field);
		if (value != null && value < 0) {
			throw new IllegalArgumentException(field + " must not be negative, received: " + value);
		}
		return value;
	}

	private static Integer parseOptionalInt(String raw, String field) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.valueOf(raw.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(field + " must be a whole number, received: " + raw);
		}
	}


	/**
	 * Endpoint to get assessments for a candidate by email.
	 *
	 * @param candidateEmail Email of the candidate
	 * @return ResponseEntity with list of assessments or error message
	 */
	@GetMapping("getCandidateAssessments/{candidateEmail}")
	public ResponseEntity<?> getCandidateAssessments(@PathVariable String candidateEmail) {
		try {
			List<Assessment> assessments = assessmentService.getCandidateAssessments(candidateEmail);
			return ResponseEntity.ok(assessments);
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
		}
	}

	/**
	 * Endpoint to fetch details of a specific assessment by ID.
	 *
	 * @param id Assessment ID
	 * @return ResponseEntity with assessment details
	 */
	@GetMapping("fetchAssessment/{id}")
	public ResponseEntity<Map<String, Object>> fetchAssessmentDetails(@PathVariable Long id) {
		Assessment assessment = assessmentService.fetchAssessmentDetails(id);
		Map<String, Object> response = new HashMap<>();
		response.put("assessmentType", assessment.getAssessmentType());
		response.put("questions", assessment.getQuestionPaper()); // Assuming this column contains JSON string
		response.put("jobPrefix", assessment.getJobPrefix());
		return ResponseEntity.ok(response);
	}

	
	

	/**
	 * Endpoint to submit a result for an assessment.
	 *
	 * @param candidateEmail Candidate's email
	 * @param assessmentType Type of assessment
	 * @param score          Score obtained
	 * @param assessmentId   The assessment sat, when the exam page knows it. Optional
	 *                       so older clients keep working, but without it a re-sent
	 *                       exam cannot be told apart from its earlier attempt.
	 * @return ResponseEntity with status and message
	 */
	@PostMapping("/result")
	@PreAuthorize("hasAuthority('ASSESSMENT_RESULT_SUBMIT')")
	public ResponseEntity<Map<String, Object>> resultAssessment(
	    @RequestParam String candidateEmail,
	    @RequestParam String assessmentType,
	    @RequestParam Double score,
	    @RequestBody String resultsJson,
	    @RequestParam String jobPrefix,
	    @RequestParam(required = false) Long assessmentId,
	    @RequestParam(required = false) Double percentage,
	    @RequestParam(required = false) Double totalMarks
	) {
	    String message = assessmentService.resultAssessment(candidateEmail, assessmentType, score, resultsJson,
	            jobPrefix, assessmentId, percentage, totalMarks);

	    Map<String, Object> response = new HashMap<>();
	    response.put("message", message);
	    response.put("status", "success");
	    return ResponseEntity.ok(response);
	}


	/**
	 * Endpoint to submit an assessment by ID. Accessible by users with ROLE_USER.
	 *
	 * @param id Assessment ID
	 * @return ResponseEntity with status and message
	 */
	@PostMapping("/submit/{id}")
	@PreAuthorize("hasAuthority('ASSESSMENT_SUBMIT')")
	public ResponseEntity<Map<String, Object>> submitAssessment(@PathVariable Long id) {
		try {
			// Call service method to submit assessment
			String message = assessmentService.submitAssessment(id);

			// Prepare success response
			Map<String, Object> response = new HashMap<>();
			response.put("message", message);
			response.put("status", "success");
			return ResponseEntity.ok(response);
		} catch (EntityNotFoundException e) {
			// Handle not found error
			Map<String, Object> response = new HashMap<>();
			response.put("message", e.getMessage());
			response.put("status", "error");
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
		}
	}

	/**
	 * Endpoint to fetch assessments, optionally filtered by candidate email or
	 * fetch all.
	 *
	 * @param candidateEmail Optional candidate email
	 * @param fetchAll       Flag to fetch all assessments
	 * @return ResponseEntity with list of assessments
	 */
	@GetMapping("/getAssessments")
	public ResponseEntity<List<Assessment>> getAssessments(@RequestParam(required = false) String candidateEmail,
			@RequestParam(required = false, defaultValue = "false") boolean fetchAll) {
		return ResponseEntity.ok(assessmentService.getAssessments(candidateEmail, fetchAll));
	}

	/**
	 * Scheduled task to expire assessments periodically. Runs every hour as per the
	 * cron expression.
	 */
	@Scheduled(cron = "0 0 * * * *") // Runs every hour
	public void scheduleExpireAssessments() {
		assessmentService.expireAssessments();

	}

	/**
	 * Retrieves results based on the candidate's email.
	 *
	 * @param email The candidate's email address.
	 * @return A list of Result entities.
	 */
//	@GetMapping("/get-results/{email}")
//	public ResponseEntity<List<Result>> getResultsByEmail(@PathVariable("email") String email) {
//		try {
//			List<Result> results = assessmentService.getResultsByEmail(email);
//
//			return ResponseEntity.ok(results);
//		} catch (IllegalArgumentException e) { 
//			return ResponseEntity.badRequest().body(null);
//		}
//	}
	
	@GetMapping("/get-results")
	public ResponseEntity<List<Result>> getResultsByEmailAndJobPrefix(
	    @RequestParam String email,
	    @RequestParam String jobPrefix
	) {
	    try {
	        List<Result> results = assessmentService.getResultsByEmailAndJobPrefix(email, jobPrefix);
	        return ResponseEntity.ok(results);
	    } catch (IllegalArgumentException e) {
	        return ResponseEntity.badRequest().body(null);
	    }
	}

	@GetMapping("/get-results-by-job-prefix")
	@PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
	public ResponseEntity<List<Result>> getResultsByJobPrefix(@RequestParam String jobPrefix) {
	    List<Result> results = resultRepository.findByJobPrefix(jobPrefix);
	    return ResponseEntity.ok(results);
	}

	/**
	 * Every assignment on a job, as slim projections.
	 *
	 * <p>Companion to {@code /get-results-by-job-prefix}. A results table grades
	 * each attempt against its own paper's pass mark, and the only way to read
	 * those was {@code /getAssessments?candidateEmail=} — one request per
	 * candidate, so reviewing a 500-candidate job opened 500 connections before
	 * a single row could be graded. This answers for the whole cohort at once.</p>
	 *
	 * <p>Returns {@link AssessmentSummaryDTO}, not {@code Assessment}: the entity
	 * carries the question paper and the answer key, and handing a reviewer every
	 * paper on the job to display a pass mark would trade one problem for a
	 * larger one.</p>
	 *
	 * @param jobPrefix the job identifier prefix
	 * @return one row per assignment, oldest first per candidate
	 */
	@GetMapping("/assessments/by-job-prefix")
	@PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
	public ResponseEntity<List<AssessmentSummaryDTO>> getAssessmentSummariesByJobPrefix(
			@RequestParam String jobPrefix) {
		return ResponseEntity.ok(assessmentRepository.findSummariesByJobPrefix(jobPrefix));
	}


	/**
	 * Endpoint to fetch a Result by its ID.
	 *
	 * @param id The ID of the Result entity.
	 * @return The Result entity if found, or a 404 response if not found.
	 */
	@GetMapping("/get-results-by-id/{id}")
	public ResponseEntity<?> getResultById(@PathVariable Long id) {
		Optional<Result> result = assessmentService.getResultById(id);
		if (result.isPresent()) {
			return result.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
		} else {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No result found for the given ID.");
		}
	}

	@GetMapping("/question-paper")
    public ResponseEntity<String> getQuestionPaper(@RequestParam String email,
                                                   @RequestParam String jobPrefix,
                                                   @RequestParam String assessmentType) {

        String paper = assessmentService.getLatestQuestionPaper(
                email, jobPrefix, assessmentType);

        return new ResponseEntity<>(paper, HttpStatus.OK);
    }
	
	/**
	 * Moves the exam window of a paper that has not been sat.
	 *
	 * <p>Times arrive as the picker writes them, {@code yyyy-MM-dd'T'HH:mm}, the
	 * same shape the assign endpoint takes.</p>
	 *
	 * @param id      the assessment to move
	 * @param request startTime, deadline, and whether to email the candidate
	 * @return the updated window
	 */
	@PatchMapping("/assessments/{id}/schedule")
	@PreAuthorize("hasAuthority('ASSESSMENT_ASSIGN')")
	public ResponseEntity<Map<String, Object>> rescheduleAssessment(@PathVariable Long id,
			@RequestBody Map<String, Object> request) {
	
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
		String startRaw = (String) request.get("startTime");
		String deadlineRaw = (String) request.get("deadline");
		// Defaults to notifying: the candidate was told the old window, so a silent
		// move leaves them turning up to an exam that is no longer there.
		boolean notify = !Boolean.FALSE.equals(request.get("notify"));
	
		Map<String, Object> response = new HashMap<>();
		if (startRaw == null || deadlineRaw == null) {
			response.put("status", "error");
			response.put("message", "Both startTime and deadline are required.");
			return ResponseEntity.badRequest().body(response);
		}
	
		LocalDateTime start;
		LocalDateTime deadline;
		try {
			start = LocalDateTime.parse(startRaw, formatter);
			deadline = LocalDateTime.parse(deadlineRaw, formatter);
		} catch (DateTimeParseException e) {
			response.put("status", "error");
			response.put("message", "Times must look like yyyy-MM-ddTHH:mm.");
			return ResponseEntity.badRequest().body(response);
		}
	
		Assessment updated = assessmentService.rescheduleAssessment(id, start, deadline, notify);
	
		response.put("status", "success");
		response.put("message", notify
				? "Exam window updated and the candidate has been emailed."
				: "Exam window updated.");
		response.put("startTime", updated.getStartTime());
		response.put("deadline", updated.getDeadline());
		response.put("expired", updated.isExpired());
		return ResponseEntity.ok(response);
	}
	
	@PostMapping("/markExamAttended")
    public ResponseEntity<?> markExamAttended(@RequestBody Map<String, Object> request) {
        String candidateEmail = (String) request.get("candidateEmail");
        Long assessmentId = Long.parseLong(request.get("assessmentId").toString());
      //  String jobPrefix = (String) request.get("jobPrefix");

        LocalDateTime startedAt = assessmentService.markExamAsAttended(candidateEmail, assessmentId);

        // The start instant goes back so the paper counts down to a fixed
        // moment rather than restarting from the full duration on every load.
        // It is stamped once, on the first attendance, so a candidate who
        // reloads at the ninety-minute mark gets the same answer they would
        // have got ninety minutes ago — reloading used to buy a whole fresh
        // paper's worth of time.
        Map<String, Object> body = new HashMap<>();
        body.put("examStartedAt", startedAt);
        return ResponseEntity.ok(body);
    }

	
	
	@PostMapping("/assign-blob")
	@PreAuthorize("hasAuthority('ASSESSMENT_ASSIGN')")
	public ResponseEntity<Map<String, String>> assignAssessmentBlob(
			@RequestPart("candidateEmails") String candidateEmails,
			@RequestPart("startTime") String startTime,
			@RequestPart("deadline") String deadline,
			@RequestPart("file") MultipartFile file,
			@RequestPart("fileName") String fileName,
			@RequestPart("uploadedBy") String uploadedBy,
			@RequestPart("jobPrefix") String jobPrefix,
			@RequestPart("assessmentType") String assessmentType
	) {
		Map<String, String> response = new HashMap<>();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
		LocalDateTime parsedStartTime = LocalDateTime.parse(startTime, formatter);
		LocalDateTime parsedDeadline = LocalDateTime.parse(deadline, formatter);

		if (parsedStartTime.isAfter(parsedDeadline)) {
			response.put("status", "error");
			response.put("message", "Start time must be before deadline");
			return ResponseEntity.badRequest().body(response);
		}

		AssignAssessmentBlobDto dto = new AssignAssessmentBlobDto();
		dto.setCandidateEmails(List.of(candidateEmails.split(",")));
		dto.setStartTime(parsedStartTime);
		dto.setDeadline(parsedDeadline);
		dto.setFile(file);
		dto.setFileName(fileName);
		dto.setUploadedBy(uploadedBy);
		dto.setJobPrefix(jobPrefix);
		dto.setAssessmentType(assessmentType);

		assessmentService.assignAssessmentToStorage(dto);

		response.put("status", "success");
		response.put("message", "Assessments assigned with storage successfully");
		return ResponseEntity.ok(response);
	}
	
	@GetMapping("/assessments/{id}/content")
	public ResponseEntity<?> getAssessmentFileContent(@PathVariable Long id) throws Exception {
		Assessment assessment = assessmentRepository.findById(id)
				.orElseThrow(() -> new RuntimeException("Assessment not found with id: " + id));

		String containerName = assessment.getContainerName();
		String fileName = assessment.getFileName();

		String fileContent = assessmentService.downloadFileContentFromStorage(containerName, fileName);

		ObjectMapper objectMapper = new ObjectMapper();
		List<Map<String, Object>> questions = objectMapper.readValue(
				fileContent, new TypeReference<List<Map<String, Object>>>() {}
		);

		return ResponseEntity.ok(questions);
	}

	/**
	 * Endpoint serving one assessment's paper together with the timing that
	 * governs it, so the exam screen can size its clock from the real question
	 * count and the allowance the admin chose.
	 *
	 * @param id Assessment ID
	 * @return The paper plus {@code minutesPerQuestion} / {@code durationMinutes},
	 *         each omitted when the assessment predates per-question timing
	 */
	@GetMapping("/assessment-content/{id}")
	public ResponseEntity<AssessmentContentDto> getAssessmentContent(@PathVariable Long id) {
		return ResponseEntity.ok(assessmentService.getAssessmentContent(id));
	}

	@GetMapping("/assessments/content/latest")
	public ResponseEntity<?> getLatestAssessmentContent(
	        @RequestParam String jobPrefix,
	        @RequestParam String candidateEmail,
	        @RequestParam String assessmentType) {
	    List<Map<String, Object>> questions =
	            assessmentService.getLatestAssessmentContent(jobPrefix, candidateEmail, assessmentType);

	    return ResponseEntity.ok(questions);
	}

	


	
	
}