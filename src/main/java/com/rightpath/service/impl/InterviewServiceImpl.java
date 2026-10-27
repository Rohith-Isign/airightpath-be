package com.rightpath.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.InterviewQuestionInfo;
import com.rightpath.dto.StartInterviewResponse;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewQuestion;
import com.rightpath.entity.InterviewSession;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.EmailType;
import com.rightpath.enums.InterviewResult;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewQuestionRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.service.InterviewReportService;
import com.rightpath.service.InterviewService;
import com.rightpath.service.JobPromptService;
import com.rightpath.service.OpenAiService;
import com.rightpath.service.StorageService;
import com.rightpath.util.StatusTransitionValidator;

@Service
@lombok.extern.slf4j.Slf4j
public class InterviewServiceImpl implements InterviewService {

	@Value("${aws.s3.prefix.interview:interview}")
	private String interviewPrefix;

	/** Every recording upload in flight, so an abandoned one can be finished by the server. */
	@Autowired
	private com.rightpath.service.RecordingUploadCoordinator recordingUploads;
	
	@Autowired
	private InterviewQuestionsServiceImpl interviewQuestionsService;
	
	@Autowired
	private InterviewQuestionRepository interviewQuestionRepository;
	
	private final Map<Long, InterviewSession> sessionStore = new HashMap<>();

	private final OpenAiService openAiService;
	private final JobApplicationForCandidateRepository jobAppRepo;
	private final CandidateInterviewScheduleRepository scheduleRepo;
	private final StorageService storageService;
	private final JobPromptService jobPromptService;
	private final com.rightpath.service.EmailAsyncService emailAsyncService;

	public InterviewServiceImpl(OpenAiService openAiService, CandidateInterviewScheduleRepository scheduleRepo,
			JobApplicationForCandidateRepository jobAppRepo,
			InterviewReportService interviewReportService, StorageService storageService,
			JobPromptService jobPromptService, com.rightpath.service.EmailAsyncService emailAsyncService) {

		this.openAiService = openAiService;
		this.jobAppRepo = jobAppRepo;
		this.scheduleRepo = scheduleRepo;
		this.storageService = storageService;
		this.jobPromptService = jobPromptService;
		this.emailAsyncService = emailAsyncService;
	}

//	@Override
//	public StartInterviewResponse start(String jobPrefix, String email, String resumeSummary) {
//
//		String systemPrompt = jobPromptService.buildStartPrompt(jobPrefix, resumeSummary);
//
//		String firstQuestion = openAiService.ask(systemPrompt);
//
//		return new StartInterviewResponse(systemPrompt, firstQuestion);
//	}
	@Override
	public StartInterviewResponse start(String jobPrefix, String email, String resumeSummary) {
	    // fetch schedule
	    CandidateInterviewSchedule schedule = scheduleRepo
	            .findTopByJobPrefixAndEmailOrderByAssignedAtDesc(jobPrefix, email)
	            .orElseThrow(() -> new ResourceNotFoundException("Interview not assigned"));

	    Long interviewScheduleId = schedule.getId();

	    // load questions (now returns List<InterviewQuestionInfo>)
	    List<InterviewQuestionInfo> questions = interviewQuestionsService.loadAndPrepareQuestions(jobPrefix);

	    // persist question usage counts
	    recordQuestionsForInterview(interviewScheduleId, questions);

	    // create session
	    InterviewSession session = new InterviewSession();
	    session.setJobPrefix(jobPrefix);
	    session.setEmail(email);
	    session.setQuestions(questions);
	    session.setCurrentQuestionIndex(0);
	    session.setResumeSummary(resumeSummary);
	    System.out.println("Saving session with " + questions.size() + " questions");
	    saveSession(interviewScheduleId, session);

	    String firstQuestion = questions.get(0).getText();   // text of the first question
	    return new StartInterviewResponse(null, firstQuestion);
	}

	private void recordQuestionsForInterview(Long scheduleId, List<InterviewQuestionInfo> questions) {
	    // Group by uniqueId – use getter method reference
	    Map<String, Long> countByUniqueId = questions.stream()
	            .collect(Collectors.groupingBy(InterviewQuestionInfo::getUniqueId, Collectors.counting()));

	    for (Map.Entry<String, Long> entry : countByUniqueId.entrySet()) {
	        String uniqueId = entry.getKey();
	        long count = entry.getValue();

	        Optional<InterviewQuestion> existing = interviewQuestionRepository
	                .findByInterviewScheduleIdAndUniqueId(scheduleId, uniqueId);

	        if (existing.isPresent()) {
	            InterviewQuestion iq = existing.get();
	            iq.setCount(iq.getCount() + (int) count);
	            interviewQuestionRepository.save(iq);
	        } else {
	            // retrieve the first occurrence of this uniqueId in the list
	            InterviewQuestionInfo first = questions.stream()
	                    .filter(q -> q.getUniqueId().equals(uniqueId))
	                    .findFirst()
	                    .orElseThrow(() -> new ResourceNotFoundException("Question not found for uniqueId: " + uniqueId));

	            InterviewQuestion newQ = new InterviewQuestion();
	            newQ.setInterviewScheduleId(scheduleId);
	            newQ.setUniqueId(uniqueId);
	            newQ.setLevel(first.getLevel());
	            newQ.setQuestionText(first.getText());   // don't forget to store the question text
	            newQ.setCount((int) count);
	            interviewQuestionRepository.save(newQ);
	        }
	    }
	}
//	@Override
//	public String answer(Long interviewScheduleId, String conversationHistory, boolean finalAnswer, String jobPrefix) {
//
//		if (conversationHistory == null || conversationHistory.isBlank()) {
//			throw new IllegalArgumentException("Conversation history cannot be null or empty");
//		}
//
//		if (finalAnswer) {
//			if (jobPrefix == null || jobPrefix.isBlank()) {
//				throw new IllegalArgumentException("jobPrefix cannot be null or empty");
//			}
//			String summary = generateFinalSummary(conversationHistory, jobPrefix);
//
//			markCompleted(interviewScheduleId, AttemptStatus.COMPLETED, InterviewResult.PENDING, summary);
//
//			return summary;
//		}
//
//		return openAiService.ask(conversationHistory);
//	}
	
	@Override
	@Deprecated
	public String answer(Long interviewScheduleId,
	                     String answerText,
	                     boolean finalAnswer,
	                     String jobPrefix,
	                     String codeContent,
	                     String codeLanguage) {
	    // Kept for callers that predate run-output capture. Treated as "the
	    // candidate never ran their code", which is what the absence means.
	    return answer(interviewScheduleId, answerText, finalAnswer, jobPrefix, codeContent, codeLanguage, null);
	}

	@Override
	public String answer(Long interviewScheduleId,
	                     String answerText,
	                     boolean finalAnswer,
	                     String jobPrefix,
	                     String codeContent,
	                     String codeLanguage,
	                     String codeOutput) {

	    InterviewSession session = getSession(interviewScheduleId);
	    List<InterviewQuestionInfo> questions = session.getQuestions();
	    System.out.println("Total questions: " + questions.size());
	    System.out.println("Session has " + session.getQuestions().size() + " questions");
	    int currentIndex = session.getCurrentQuestionIndex();
	    System.out.println("Current index: " + currentIndex);
	    boolean retryAlreadyUsed = session.getRetryUsed().getOrDefault(currentIndex, false);
	    System.out.println("Retry already used for index " + currentIndex + ": " + retryAlreadyUsed);

	    // Build the full answer (with code if any)
	    String answerWithCode = answerText;
	    if (codeContent != null && !codeContent.isBlank()) {
	        answerWithCode += "\n\n[CODE (" + (codeLanguage != null ? codeLanguage : "text") + ")]\n" + codeContent;
	    }

	    // Append to history ONLY if we are not in a retry situation
	    // We'll store after the retry decision

	    if (finalAnswer) {
	        session.appendQAPair(questions.get(currentIndex).getText(), answerWithCode);
	        String fullHistory = session.getQaHistoryAsString();
	        return generateFinalSummary(fullHistory, jobPrefix);
	    }

	    // Build evaluation prompt (same as before, includes language warning instruction)
	    String systemPrompt = jobPromptService.buildStartPrompt(jobPrefix, session.getResumeSummary());
	    String evaluationPrompt = systemPrompt + "\n\n" +
	            "If candidate answer other than English, warn him and ask him to answer in English only.\n" +
	            "Now evaluate the following answer. Your response must include:\n" +
	            "1. Classification (choose one):\n" +
	            "   - Base answered: Totally correct\n" +
	            "   - Base answered: Partially correct\n" +
	            "   - Base not answered\n" +
	            "   - Level 1 answered\n" +
	            "   - Level 2 answered\n" +
	            "2. Short, constructive feedback:\n" +
	            "   - If correct: acknowledge briefly.\n" +
	            "   - If partially correct: guide improvement.\n" +
	            "   - If incorrect: explain the key concept briefly.\n\n" +
	            "Focus on technical correctness and clarity of explanation.\n" +
	            "For coding answers, check correct logic, code structure, and explanation clarity.\n" +
	            "Be professional and concise. Do NOT ask questions or generate new questions.\n\n" +
	            "Question: " + questions.get(currentIndex).getText() + "\n" +
	            "Candidate's Answer: " + answerWithCode;

	    String aiFeedback = openAiService.ask(evaluationPrompt);
	    
	    

	    // --- Check if AI suggests the candidate should answer in English ---
	    boolean isLanguageWarning = aiFeedback.toLowerCase().contains("answer in english") ||
                aiFeedback.toLowerCase().contains("non-english") ||
                aiFeedback.toLowerCase().contains("english only");

			if (!retryAlreadyUsed && isLanguageWarning) {
			// First non-English attempt: give a retry
			session.getRetryUsed().put(currentIndex, true);
			// Do NOT store the answer, do NOT advance index
			return "RETRY: " + aiFeedback + "\n\nPlease answer the same question again.\n\nQuestion: " + questions.get(currentIndex).getText();
			}
			
			//Normal flow (English answer, or second attempt regardless of language)
			session.appendQAPair(questions.get(currentIndex).getText(), answerWithCode);
			int nextIndex = currentIndex + 1;
			session.setCurrentQuestionIndex(nextIndex);

	    if (nextIndex >= questions.size()) {
	        String fullHistory = session.getQaHistoryAsString();
	        return generateFinalSummary(fullHistory, jobPrefix);
	    }

	    InterviewQuestionInfo nextQuestion = questions.get(nextIndex);
	    System.out.println("AI Feedback: " + aiFeedback);
	    String returnValue = "FEEDBACK: " + aiFeedback + "\n\nNEXT QUESTION: " + nextQuestion.getText();
	    System.out.println("Returning: " + returnValue);
	    return "FEEDBACK: " + aiFeedback + "\n\nNEXT QUESTION: " + nextQuestion.getText();
	}
	
//	private String generateFinalSummary(String conversationHistory, String jobPrefix) {
//
//		String summaryPrompt = jobPromptService.buildSummaryPrompt(jobPrefix, conversationHistory);
//
//		summaryPrompt = summaryPrompt
//				+ "\n\nProvide all the questions and answers in order, followed by a final summary. "
//				+ "Do not include the system prompt in your response.";
//
//		return openAiService.ask(summaryPrompt);
//	}

//	private String generateFinalSummary(String conversationHistory, String jobPrefix) {
//	    return "HISTORY:\n" + conversationHistory;  
//	}
	
	private String generateFinalSummary(String conversationHistory, String jobPrefix) {
	    String summaryPrompt = 
	        "Please provide a final summary of this interview based on the following Q&A.\n" +
	        "List all questions and answers in order, then provide an overall assessment.\n\n" +
	        "Q&A History:\n" + conversationHistory;
	    return openAiService.ask(summaryPrompt);
	}
	
	@Override
	public CandidateInterviewSchedule assignInterview(String jobPrefix, String email, LocalDateTime assignedAt,
	        LocalDateTime deadlineTime, LocalDate questionsFromDate, LocalDate questionsToDate) {

	    requireDeadline(deadlineTime);

	    JobApplicationForCandidate application = jobAppRepo.findByJobPost_JobPrefixAndUser_Email(jobPrefix, email)
	            .orElseThrow(() -> new ResourceNotFoundException("Application not found"));

	    application.setInterview("Scheduled");
	    jobAppRepo.save(application);

	    CandidateInterviewSchedule schedule = CandidateInterviewSchedule.builder()
	            .jobPrefix(jobPrefix)
	            .email(email)
	            .attemptStatus(AttemptStatus.NOT_ATTEMPTED)
	            .interviewResult(InterviewResult.PENDING)
	            .assignedAt(assignedAt)
	            .deadlineTime(deadlineTime)
	            .questionsFromDate(questionsFromDate != null ? questionsFromDate.atStartOfDay() : null)
	            .questionsToDate(questionsToDate != null ? questionsToDate.atTime(23, 59, 59) : null)
	            .build();

	    return scheduleRepo.save(schedule);
	}

	
	
	/**
	 * An interview without a date and time has no deadline: it never expires, never
	 * chases the candidate, and cannot be tracked. The scheduling screens ask for it,
	 * but nothing on the server stopped a request that left it out.
	 */
	private static void requireDeadline(LocalDateTime deadlineTime) {
		if (deadlineTime == null) {
			throw new IllegalArgumentException("Date and time is required to assign an interview");
		}
	}

	@Override
	public List<CandidateInterviewSchedule> getActiveInterviewsByEmail(String email) {
		return scheduleRepo.findActiveInterviewsByEmail(email);
	}

	@Override
	@Deprecated
	public List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, List<String> emails,
	        LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail,
	        LocalDate questionsFromDate, LocalDate questionsToDate) {
	    return assignInterviewBulk(jobPrefix, emails, assignedAt, deadlineTime, sendEmail,
	            questionsFromDate, questionsToDate, null);
	}

	@Override
	public List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, List<String> emails,
	        LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail,
	        LocalDate questionsFromDate, LocalDate questionsToDate,
	        com.rightpath.enums.InterviewRound round) {

	    if (emails == null || emails.isEmpty()) {
	        throw new IllegalArgumentException("emails must be provided");
	    }
	    requireDeadline(deadlineTime);

	    List<CandidateInterviewSchedule> schedules = new ArrayList<>();
	    // Kept so the invitation can address the candidate by name and quote the
	    // role, instead of the bare job prefix they never see anywhere else.
	    Map<String, JobApplicationForCandidate> applicationsByEmail = new HashMap<>();

	    for (String email : emails) {
	        JobApplicationForCandidate application = jobAppRepo
	                .findByJobPost_JobPrefixAndUser_Email(jobPrefix, email)
	                .orElseThrow(() -> new ResourceNotFoundException("Application not found for " + email));

	        application.setInterview("Scheduled");

	        // Advance the pipeline, not just the free-text column.
	        //
	        // Scheduling an interview used to set `interview = "Scheduled"` and
	        // leave `status` on EXAM_COMPLETED, so the candidate sat in the exam
	        // stage forever while a second field claimed otherwise — and every
	        // screen that reads status showed them as not yet progressed.
	        //
	        // Guarded rather than validated: re-scheduling an interview is a
	        // normal thing to do, and INTERVIEW_SCHEDULED → INTERVIEW_SCHEDULED is
	        // not a legal move, so throwing here would make the second attempt
	        // fail and create no schedule at all.
	        if (StatusTransitionValidator.isAllowed(application.getStatus(), ApplicationStatus.INTERVIEW_SCHEDULED)) {
	            application.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
	        }

	        jobAppRepo.save(application);
	        applicationsByEmail.put(email, application);

	        CandidateInterviewSchedule schedule = CandidateInterviewSchedule.builder()
	                .jobPrefix(jobPrefix)
	                .email(email)
	                .attemptStatus(AttemptStatus.NOT_ATTEMPTED)
	                .interviewResult(InterviewResult.PENDING)
	                .assignedAt(assignedAt)
	                .deadlineTime(deadlineTime)
	                .questionsFromDate(questionsFromDate != null ? questionsFromDate.atStartOfDay() : null)
	                .questionsToDate(questionsToDate != null ? questionsToDate.atTime(23, 59, 59) : null)
	                // Normalised rather than stored as null, so a schedule created
	                // now always says which interview it is. Only rows predating
	                // the column read as null.
	                .round(com.rightpath.enums.InterviewRound.orDefault(round))
	                .build();

	        schedules.add(schedule);
	    }

	    schedules = scheduleRepo.saveAll(schedules);

	    if (sendEmail) {
	        for (CandidateInterviewSchedule s : schedules) {
	            JobApplicationForCandidate application = applicationsByEmail.get(s.getEmail());

	            Map<String, Object> emailParams = new HashMap<>();
	            emailParams.put("recipientEmail", s.getEmail());
	            emailParams.put("jobPrefix", jobPrefix);
	            // The template formats these for display; never hand it a pre-formatted
	            // or ISO string.
	            emailParams.put("startTime", assignedAt);
	            emailParams.put("endTime", deadlineTime);
	            if (application != null) {
	                emailParams.put("firstName", application.getFirstName());
	                emailParams.put("lastName", application.getLastName());
	                emailParams.put("mobileNumber", application.getMobileNumber());
	                if (application.getJobPost() != null) {
	                    emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	                }
	            }

	            emailAsyncService.sendTemplatedEmail(EmailType.INTERVIEW_SCHEDULE, emailParams);
	        }
	    }

	    return schedules;
	}

	

	@Transactional
	@Override
	public void markCompleted(Long interviewScheduleId, AttemptStatus attemptStatus, InterviewResult interviewResult,
			String summaryRef) {
		CandidateInterviewSchedule schedule = scheduleRepo.findById(interviewScheduleId)
				.orElseThrow(() -> new ResourceNotFoundException("Interview not found"));

		schedule.setAttemptStatus(attemptStatus);
		schedule.setInterviewResult(interviewResult);
		schedule.setSummaryReferences(summaryRef);

		scheduleRepo.save(schedule);

		// If interview completed, update the job application status
		if (attemptStatus == AttemptStatus.COMPLETED) {
			jobAppRepo.findByJobPost_JobPrefixAndUser_Email(schedule.getJobPrefix(), schedule.getEmail())
					.ifPresent(application -> {
						if (application.getStatus() == ApplicationStatus.INTERVIEW_SCHEDULED) {
							StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.INTERVIEW_COMPLETED);
							application.setStatus(ApplicationStatus.INTERVIEW_COMPLETED);
							application.setInterview("Completed");
							jobAppRepo.save(application);
						}
					});
		}
	}

	// Deliberately not @Transactional. The upload to S3 takes as long as the file
	// is big, and a transaction around it held a database connection for all of
	// that and, worse, a copy of the interview row that was stale by the time it
	// was written back. The row is touched once, afterwards, by a single UPDATE
	// of the one column.
	@Override
	public String storeRecording(Long interviewScheduleId, MultipartFile videoFile) {

		if (videoFile == null || videoFile.isEmpty()) {
			throw new IllegalArgumentException("Video file is required");
		}

		if (!scheduleRepo.existsById(interviewScheduleId)) {
			throw new ResourceNotFoundException("Interview not found");
		}

		String blobName = buildBlobName(interviewScheduleId, videoFile.getOriginalFilename());

		// See storeScreenRecording: the presence or absence of this line is how
		// you tell a rejected request from a failed upload.
		log.info("Camera recording for schedule {} arrived: {} bytes", interviewScheduleId, videoFile.getSize());

		String videoUrl = storageService.uploadFile(interviewPrefix, blobName, videoFile);
		log.info("Camera recording for schedule {} stored at {}", interviewScheduleId, videoUrl);

		// Appended, not replaced, exactly as the screen recording is. The
		// browser now uploads the camera in parts while the interview runs, so
		// a tab that is closed or put to sleep loses minutes rather than the
		// whole hour. Overwriting would keep only the last part and discard
		// everything uploaded before it.
		// One UPDATE in the database rather than read, extended and saved: that
		// read-modify-write is how earlier parts were lost whenever anything else
		// wrote the row in between.
		scheduleRepo.appendRecordReference(interviewScheduleId, videoUrl, "\n" + videoUrl);

		return videoUrl;
	}

	// Not @Transactional, for the reason given on storeRecording.
	@Override
	public String storeScreenRecording(Long interviewScheduleId, MultipartFile screenFile) {

		if (screenFile == null || screenFile.isEmpty()) {
			throw new IllegalArgumentException("Screen recording file is required");
		}

		if (!scheduleRepo.existsById(interviewScheduleId)) {
			throw new ResourceNotFoundException("Interview not found");
		}

		String blobName = buildBlobName(interviewScheduleId, screenFile.getOriginalFilename());

		// Logged on arrival, before storage is attempted. When a recording goes
		// missing the first question is always whether it reached the server at
		// all, and the absence of this line answers it — a request rejected by a
		// proxy for its size never gets here, so nothing is logged and that
		// silence is itself the diagnosis.
		log.info("Screen recording for schedule {} arrived: {} bytes", interviewScheduleId, screenFile.getSize());

		String screenUrl = storageService.uploadFile(interviewPrefix, blobName, screenFile);
		log.info("Screen recording for schedule {} stored at {}", interviewScheduleId, screenUrl);

		// Appended, not replaced. Screen sharing can stop and be picked up
		// again mid-interview, and each share is uploaded as its own file:
		// every MediaRecorder run emits a complete WebM with its own header,
		// so joining them would leave a recording that plays only as far as
		// the first boundary. Overwriting instead kept the last part and threw
		// away everything the candidate did before they stopped sharing.
		//
		// Newline-separated because a stored reference is a key built from the
		// uploaded file's own name, which may contain a comma but cannot
		// contain a line break.
		// One UPDATE in the database, not a read-modify-write of the row.
		scheduleRepo.appendScreenRecordReference(interviewScheduleId, screenUrl, "\n" + screenUrl);

		return screenUrl;
	}

	@Override
	public java.util.Map<String, String> beginRecordingUpload(Long interviewScheduleId, String kind) {
		return beginRecordingUpload(interviewScheduleId, kind, null);
	}

	@Override
	public java.util.Map<String, String> beginRecordingUpload(Long interviewScheduleId, String kind,
			String contentType) {
		if (!scheduleRepo.existsById(interviewScheduleId)) {
			throw new ResourceNotFoundException("Interview not found");
		}
		String recordingKind = recordingKindOf(kind);
		String filePrefix = recordingKind.equals("screen") ? "screen-"
				: recordingKind.equals("mobile") ? "mobile-" : "interview-";
		// The extension is what the playback link is typed from, so it has to
		// match what was recorded. iPhones record MP4; everything else WebM.
		boolean mp4 = contentType != null && contentType.toLowerCase().startsWith("video/mp4");
		String blobName = buildBlobName(interviewScheduleId, filePrefix + interviewScheduleId + (mp4 ? ".mp4" : ".webm"));
		String uploadId = storageService.beginMultipartUpload(interviewPrefix, blobName, mp4 ? "video/mp4" : "video/webm");
		recordingUploads.opened(interviewScheduleId, recordingKind, blobName, uploadId);
		log.info("{} recording for schedule {}: piecewise upload started", recordingKind, interviewScheduleId);
		return java.util.Map.of("uploadId", uploadId, "blobName", blobName);
	}

	@Override
	public void uploadRecordingPart(Long interviewScheduleId, String blobName, String uploadId, int part,
			byte[] data) {
		requireOwnBlob(interviewScheduleId, blobName);
		if (part < 1 || part > 10_000) {
			throw new IllegalArgumentException("Part number out of range");
		}
		// Refused with 410 if the server already finished this upload, so the
		// browser stops rather than retrying for ever.
		recordingUploads.requireOpen(uploadId);
		storageService.uploadMultipartPart(interviewPrefix, blobName, uploadId, part, data);
		recordingUploads.touched(uploadId);
	}

	@Override
	public String completeRecordingUpload(Long interviewScheduleId, String kind, String blobName, String uploadId) {
		requireOwnBlob(interviewScheduleId, blobName);
		// Joined exactly once, however many times and by whoever asks — the
		// candidate's browser at the end, or the sweeper if it never came back.
		return recordingUploads.complete(interviewScheduleId, recordingKindOf(kind), blobName, uploadId);
	}

	/** camera, screen or mobile: whichever the caller named, anything else is refused. */
	private static String recordingKindOf(String kind) {
		if ("screen".equalsIgnoreCase(kind)) {
			return "screen";
		}
		if ("camera".equalsIgnoreCase(kind)) {
			return "camera";
		}
		if ("mobile".equalsIgnoreCase(kind)) {
			return "mobile";
		}
		throw new IllegalArgumentException("kind must be camera, screen or mobile");
	}

	/** The name must be one this interview's own upload produced, so a caller cannot write elsewhere. */
	private static void requireOwnBlob(Long interviewScheduleId, String blobName) {
		if (blobName == null || !blobName.startsWith("interview-" + interviewScheduleId + "/")
				|| blobName.contains("..")) {
			throw new IllegalArgumentException("Upload does not belong to this interview");
		}
	}

	@Override
	@Deprecated
	public List<CandidateInterviewSchedule> getResults(String jobPrefix) {
		return getResults(jobPrefix, null);
	}

	@Override
	public List<CandidateInterviewSchedule> getResults(String jobPrefix, com.rightpath.enums.InterviewRound round) {
		return getResults(jobPrefix, round, false);
	}

	@Override
	public List<CandidateInterviewSchedule> getResults(String jobPrefix, com.rightpath.enums.InterviewRound round,
			boolean includeDeleted) {
		List<CandidateInterviewSchedule> all = (jobPrefix != null && !jobPrefix.isBlank())
				? scheduleRepo.findAllByJobPrefix(jobPrefix)
				: scheduleRepo.findAll();

		// getEffectiveRound(), not getRound(): a null column reads as the default
		// round, so pre-rounds interviews stay visible under L2 instead of
		// disappearing from every filter.
		return all.stream()
				.filter(schedule -> includeDeleted || !schedule.isDeleted())
				.filter(schedule -> round == null || schedule.getEffectiveRound() == round)
				.toList();
	}

	@Override
	@Transactional
	public CandidateInterviewSchedule deleteResult(Long id, String reason) {
		String trimmed = reason == null ? "" : reason.trim();
		if (trimmed.isEmpty()) {
			// Refused rather than defaulted. A reason that the system made up
			// is worse than no reason, because it reads like one somebody gave.
			throw new IllegalArgumentException("A reason is required to remove an interview result.");
		}

		CandidateInterviewSchedule schedule = scheduleRepo.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Interview not found"));

		if (schedule.isDeleted()) {
			// Not idempotent on purpose: overwriting would replace the original
			// reason and the original remover with whoever clicked last.
			throw new IllegalStateException("This interview result has already been removed.");
		}

		schedule.setDeletedAt(LocalDateTime.now());
		schedule.setDeletedBy(actingUser());
		schedule.setDeleteReason(trimmed);
		CandidateInterviewSchedule saved = scheduleRepo.save(schedule);

		// Deliberately loud, and at info: this hides a hiring decision's
		// evidence from the screen people make decisions on.
		log.info("Interview result {} ({} / {}) removed by {}: {}", saved.getId(), saved.getJobPrefix(),
				saved.getEmail(), saved.getDeletedBy(), trimmed);
		return saved;
	}

	@Override
	@Transactional
	public CandidateInterviewSchedule restoreResult(Long id) {
		CandidateInterviewSchedule schedule = scheduleRepo.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Interview not found"));

		if (!schedule.isDeleted()) {
			throw new IllegalStateException("This interview result has not been removed.");
		}

		String previousReason = schedule.getDeleteReason();
		String previousRemover = schedule.getDeletedBy();
		schedule.setDeletedAt(null);
		schedule.setDeletedBy(null);
		schedule.setDeleteReason(null);
		CandidateInterviewSchedule saved = scheduleRepo.save(schedule);

		// The row no longer carries the removal, so the log is the only record
		// that it happened at all. It says what is being undone.
		log.info("Interview result {} ({} / {}) restored by {}; had been removed by {}: {}", saved.getId(),
				saved.getJobPrefix(), saved.getEmail(), actingUser(), previousRemover, previousReason);
		return saved;
	}

	/**
	 * Email of the authenticated admin, as set by the JWT filter. Falls back to
	 * "system" for non-request callers so the audit line is never blank.
	 */
	private static String actingUser() {
		org.springframework.security.core.Authentication authentication = org.springframework.security.core.context.SecurityContextHolder
				.getContext().getAuthentication();
		return (authentication == null || authentication.getName() == null) ? "system" : authentication.getName();
	}

	@Override
	public CandidateInterviewSchedule getResultDetail(Long id) {
		return scheduleRepo.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + id));
	}

	private String buildBlobName(Long interviewId, String originalName) {
		return "interview-" + interviewId + "/" + System.currentTimeMillis() + "-" + originalName;
	}

	private void saveSession(Long interviewScheduleId, InterviewSession session) {
	    sessionStore.put(interviewScheduleId, session);
	}
	
	private InterviewSession getSession(Long interviewScheduleId) {

	    InterviewSession session = sessionStore.get(interviewScheduleId);

	    if (session == null) {
	        throw new ResourceNotFoundException("Session not found for interviewScheduleId: " + interviewScheduleId);
	    }

	    return session;
	}
	
//	@Override
//	public String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId) {
//	    return prepareQuestionsAndCreateSession(jobPrefix, email, scheduleId, null, null);
//	}

	@Override
	public String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId) {
	    // Fetch schedule to get date filters
	    CandidateInterviewSchedule schedule = scheduleRepo.findById(scheduleId)
	            .orElseThrow(() -> new ResourceNotFoundException("Schedule not found: " + scheduleId));
	    
	    Long fromDate = schedule.getQuestionsFromDate() != null 
	            ? schedule.getQuestionsFromDate().toInstant(ZoneOffset.UTC).toEpochMilli() 
	            : null;
	    Long toDate = schedule.getQuestionsToDate() != null 
	            ? schedule.getQuestionsToDate().toInstant(ZoneOffset.UTC).toEpochMilli() 
	            : null;
	    
	    // Log the date range for debugging
	
	    // Load questions with date filtering
	    List<InterviewQuestionInfo> questions = interviewQuestionsService.loadAndPrepareQuestions(jobPrefix, fromDate, toDate);
	    
	    // Check if questions are loaded
	    if (questions == null || questions.isEmpty()) {
	        throw new ResourceNotFoundException("No questions found for jobPrefix: " + jobPrefix + 
	                                   " with date range from " + fromDate + " to " + toDate);
	    }
	    
	  
	    
	    recordQuestionsForInterview(scheduleId, questions);
	    
	    InterviewSession session = new InterviewSession();
	    session.setJobPrefix(jobPrefix);
	    session.setEmail(email);
	    session.setQuestions(questions);
	    session.setCurrentQuestionIndex(0);
	    session.setResumeSummary(null);
	    saveSession(scheduleId, session);
	    
	    String firstQuestion = questions.get(0).getText();
	  
	    return firstQuestion;
	}

	// DELETE THIS METHOD - it's not needed and returns null
	// @Override
	// public String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId, Long fromDate, Long toDate) {
//	     return null;
	// }

//	@Override
//	public String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId, Long fromDate,
//			Long toDate) {
//		// TODO Auto-generated method stub
//		return null;
//	}
	
}
