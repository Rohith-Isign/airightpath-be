package com.rightpath.controller;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.AssignInterviewBulkDTO;
import com.rightpath.dto.AssignInterviewDTO;
import com.rightpath.dto.CandidateInterviewScheduleDTO;
import com.rightpath.dto.DeleteInterviewResultRequest;
import com.rightpath.dto.RecordingOutcomeRequest;
import com.rightpath.dto.InterviewStatsDTO;
import com.rightpath.dto.voice.VoiceConversationEntryDTO;
import com.rightpath.enums.InterviewRound;
import com.rightpath.dto.StartInterviewRequest;
import com.rightpath.dto.StartInterviewResponse;
import com.rightpath.dto.InterviewReviewDTO;
import com.rightpath.dto.ProctoringEventDTO;
import com.rightpath.dto.InterviewReviewRequest;
import com.rightpath.entity.InterviewReview;
import com.rightpath.dto.voice.ResumeResponse;
import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.dto.voice.VoiceSessionStatus;
import com.rightpath.dto.voice.VoiceStartResponse;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.ProctoringEvent;
import com.rightpath.entity.RoomVerificationSession;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.InterviewResult;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.ProctoringEventRepository;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.service.InterviewQuestionsService;
import com.rightpath.service.InterviewService;
import com.rightpath.service.RoomVerificationService;
import com.rightpath.service.SpeechToTextService;
import com.rightpath.service.VoiceInterviewService;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {

	private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
	
	@Autowired
	private com.rightpath.service.StorageService storageService;

	@Autowired
	private InterviewQuestionsService interviewQuestionsService;

	@Autowired
	private InterviewService interviewService;
	@Autowired
	private SpeechToTextService speechToTextService;
	@Autowired
	private com.rightpath.service.EmailService emailService;
	@Autowired
	private VoiceInterviewService voiceInterviewService;
	@Autowired
	private ProctoringEventRepository proctoringEventRepository;

	@Autowired
	private CandidateInterviewScheduleRepository scheduleRepo;
	@Autowired
	private VoiceConversationEntryRepository voiceConversationEntryRepository;
	
	@Autowired
	private RoomVerificationService roomVerificationService;

	@Autowired
	private com.rightpath.service.InterviewReviewService interviewReviewService;

	@PostMapping("/assign-interview")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> assignInterview(@RequestBody AssignInterviewDTO dto) {
	    CandidateInterviewSchedule schedule = interviewService.assignInterview(
	            dto.getJobPrefix(), 
	            dto.getEmail(),
	            dto.getAssignedAt(), 
	            dto.getDeadlineTime(),
	            dto.getQuestionsFromDate(),
	            dto.getQuestionsToDate()
	    );
	    return ResponseEntity.ok(new CandidateInterviewScheduleDTO(schedule));
	}

	@PostMapping("/assign-interview-bulk")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> assignInterviewBulk(@RequestBody AssignInterviewBulkDTO dto) {
	    LocalDateTime assignedAt = dto.getAssignedAt() != null ? dto.getAssignedAt() : LocalDateTime.now();

	    List<CandidateInterviewSchedule> schedules = interviewService.assignInterviewBulk(
	            dto.getJobPrefix(),
	            dto.getEmails(),
	            assignedAt,
	            dto.getDeadlineTime(),
	            dto.isSendEmail(),
	            dto.getQuestionsFromDate(),  // LocalDate
	            dto.getQuestionsToDate(),    // LocalDate
	            dto.getRound()
	    );

	    List<CandidateInterviewScheduleDTO> results = schedules.stream()
	            .map(CandidateInterviewScheduleDTO::new)
	            .toList();

	    return ResponseEntity.ok(results);
	}

	/**
	 * @param round optional round filter; omit for all rounds. An unrecognised
	 *              value is a 400 rather than a silent "all rounds", so a typo in
	 *              a caller shows up as an error instead of wrong-looking data.
	 */
	@GetMapping("/results")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> getResults(@RequestParam(required = false) String jobPrefix,
			@RequestParam(required = false) InterviewRound round,
			@RequestParam(defaultValue = "false") boolean includeDeleted) {
		List<CandidateInterviewSchedule> results = interviewService.getResults(jobPrefix, round, includeDeleted);
		return ResponseEntity.ok(withReviews(results));
	}

	/**
	 * Removes a finished interview's result from the results list, with a reason.
	 *
	 * <p><strong>Removal is soft.</strong> The row survives with its transcript,
	 * its proctoring events, its recordings and its evaluation intact; who
	 * removed it, when and why are recorded on it and shown to anyone who asks
	 * for removed results. Nothing about a hiring decision is destroyed, which
	 * is the point — this is a tidying action on a screen, not an erasure.</p>
	 *
	 * <p>Behind its own authority rather than INTERVIEW_ASSIGN: taking a result
	 * off the screen people decide from is a different kind of act from booking
	 * interviews and reading them.</p>
	 */
	@DeleteMapping("/results/{id}")
	@PreAuthorize("hasAuthority('INTERVIEW_RESULT_DELETE')")
	public ResponseEntity<?> deleteResult(@PathVariable Long id,
			@RequestBody DeleteInterviewResultRequest request) {
		CandidateInterviewSchedule removed = interviewService.deleteResult(id,
				request == null ? null : request.getReason());
		return ResponseEntity.ok(new CandidateInterviewScheduleDTO(
				removed, interviewReviewService.find(id).orElse(null)));
	}

	/** Puts a removed result back. The counterpart to {@link #deleteResult}. */
	@PostMapping("/results/{id}/restore")
	@PreAuthorize("hasAuthority('INTERVIEW_RESULT_DELETE')")
	public ResponseEntity<?> restoreResult(@PathVariable Long id) {
		CandidateInterviewSchedule restored = interviewService.restoreResult(id);
		return ResponseEntity.ok(new CandidateInterviewScheduleDTO(
				restored, interviewReviewService.find(id).orElse(null)));
	}

	@GetMapping("/results/{id}")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> getResultDetail(@PathVariable Long id) {
		CandidateInterviewSchedule schedule = interviewService.getResultDetail(id);
		return ResponseEntity.ok(new CandidateInterviewScheduleDTO(
				schedule, interviewReviewService.find(id).orElse(null)));
	}

	/**
	 * Results with any reviewer override attached.
	 *
	 * <p>Reviews are fetched for the whole page in one query and matched in
	 * memory. Looking each one up as the DTO is built would put a query per row
	 * behind a screen that routinely renders fifty.</p>
	 */
	private List<CandidateInterviewScheduleDTO> withReviews(List<CandidateInterviewSchedule> schedules) {
		if (schedules.isEmpty()) {
			return List.of();
		}
		java.util.Map<Long, InterviewReview> byScheduleId = interviewReviewService
				.findAll(schedules.stream().map(CandidateInterviewSchedule::getId).toList())
				.stream()
				.collect(java.util.stream.Collectors.toMap(
						InterviewReview::getInterviewScheduleId, r -> r, (a, b) -> a));

		return schedules.stream()
				.map(schedule -> new CandidateInterviewScheduleDTO(
						schedule, byScheduleId.get(schedule.getId())))
				.toList();
	}

	@GetMapping("/active")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<?> getActiveInterviews(@RequestParam String email) {

		List<CandidateInterviewSchedule> list = interviewService.getActiveInterviewsByEmail(email);

		List<CandidateInterviewScheduleDTO> dtoList = list.stream().map(CandidateInterviewScheduleDTO::new).toList();

		return ResponseEntity.ok(dtoList);
	}

	@PostMapping("/start")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public StartInterviewResponse start(@RequestBody StartInterviewRequest req) {
		return interviewService.start(req.getJobPrefix(), req.getEmail(), req.getResumeSummary());
	}

//	@PostMapping("/answer")
//	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
//	public String answerQuestion(@RequestParam Long interviewScheduleId,
//			@RequestParam(required = false) String conversationHistory, @RequestParam boolean finalAnswer,
//			@RequestParam String jobPrefix) {
//		return interviewService.answer(interviewScheduleId, conversationHistory, finalAnswer, jobPrefix);
//	}
	
	@PostMapping("/answer")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public String answerQuestion(@RequestParam Long interviewScheduleId,
	                             @RequestParam String answer,
	                             @RequestParam(required = false) boolean finalAnswer,
	                             @RequestParam String jobPrefix,
	                             @RequestParam(required = false) String codeContent,
	                             @RequestParam(required = false) String codeLanguage) {
	    return interviewService.answer(interviewScheduleId, answer, finalAnswer, jobPrefix, codeContent, codeLanguage);
	}

	@PostMapping(value = "/voice-to-text", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public String convertVoiceToText(@RequestParam("file") MultipartFile file) throws Exception {

		byte[] audioBytes = file.getBytes();
		if (audioBytes == null || audioBytes.length == 0) {
			throw new IllegalArgumentException("Audio file is empty");
		}

		return speechToTextService.transcribeAudio(audioBytes, file.getOriginalFilename());
	}

	@PostMapping(value = "/{interviewScheduleId}/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public String uploadInterviewVideo(@PathVariable Long interviewScheduleId,
			@RequestParam("file") MultipartFile videoFile) {
		return interviewService.storeRecording(interviewScheduleId, videoFile);
	}

	@PostMapping(value = "/{interviewScheduleId}/screen-recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public String uploadScreenRecording(@PathVariable Long interviewScheduleId,
			@RequestParam("file") MultipartFile screenFile) {
		return interviewService.storeScreenRecording(interviewScheduleId, screenFile);
	}

	// A recording sent in pieces. See StorageService.beginMultipartUpload for why:
	// a whole recording in one request does not get through whatever sits in
	// front of the server, and a piece of a few megabytes does.
	@PostMapping("/{interviewScheduleId}/recording-upload")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Map<String, String>> beginRecordingUpload(@PathVariable Long interviewScheduleId,
			@RequestParam String kind) {
		return ResponseEntity.ok(interviewService.beginRecordingUpload(interviewScheduleId, kind));
	}

	@PostMapping(value = "/{interviewScheduleId}/recording-upload/part", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Void> uploadRecordingPart(@PathVariable Long interviewScheduleId,
			@RequestParam String uploadId, @RequestParam String blobName, @RequestParam int part,
			@RequestBody byte[] data) {
		interviewService.uploadRecordingPart(interviewScheduleId, blobName, uploadId, part, data);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/{interviewScheduleId}/recording-upload/complete")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public String completeRecordingUpload(@PathVariable Long interviewScheduleId, @RequestParam String kind,
			@RequestParam String uploadId, @RequestParam String blobName) {
		return interviewService.completeRecordingUpload(interviewScheduleId, kind, blobName, uploadId);
	}

	// ==================== Voice Interview Endpoints ====================

	@PostMapping("/voice/start")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<VoiceStartResponse> startVoiceInterview(@RequestBody StartInterviewRequest req) {
	    VoiceStartResponse response = voiceInterviewService.startVoiceInterview(
	            req.getJobPrefix(),
	            req.getEmail(),
	            req.getFromDate(),
	            req.getToDate(),
	            req.getScheduleId());
	    return ResponseEntity.ok(response);
	}
	
	
	@PostMapping("/voice/{id}/end")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<?> endVoiceInterview(@PathVariable Long id) {
		voiceInterviewService.endVoiceInterview(id);
		return ResponseEntity.ok().build();
	}

	@GetMapping("/voice/{id}/status")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<VoiceSessionStatus> getVoiceInterviewStatus(@PathVariable Long id) {
		return ResponseEntity.ok(voiceInterviewService.getSessionStatus(id));
	}

	@GetMapping("/voice/{id}/evaluation")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<VoiceEvaluationResult> getVoiceEvaluation(@PathVariable Long id) {
		return ResponseEntity.ok(voiceInterviewService.getEvaluation(id));
	}

	// Item 4: Resume endpoint
	@GetMapping("/voice/{scheduleId}/resume")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<ResumeResponse> resumeVoiceInterview(@PathVariable Long scheduleId) {
		return ResponseEntity.ok(voiceInterviewService.resumeInterview(scheduleId));
	}

	/**
	 * An interview's proctoring events, oldest first.
	 *
	 * <p>Projected rather than returned as entities. The event points back at
	 * its schedule and the schedule holds a list of its events, with neither
	 * side ignored by Jackson — so serialising one walked the cycle until the
	 * stack ran out and every call answered 500. Same failure, same fix, as the
	 * transcript beside it; see {@link ProctoringEventDTO}.</p>
	 */
	@GetMapping("/{scheduleId}/proctoring-events")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<List<ProctoringEventDTO>> getProctoringEvents(@PathVariable Long scheduleId) {
		List<ProctoringEventDTO> events = proctoringEventRepository
				.findByScheduleIdOrderByTimestampAsc(scheduleId)
				.stream()
				.map(ProctoringEventDTO::from)
				.toList();
		return ResponseEntity.ok(events);
	}

	/**
	 * Records what became of one of an interview's recordings.
	 *
	 * <p>Over HTTP, deliberately. The same report already goes over the
	 * interview WebSocket, but the failures most worth auditing — the
	 * candidate's connection dropping, the upload timing out — are the ones
	 * that have usually taken the socket with them, so the record of them
	 * never arrived. An audit that only survives when nothing went wrong is
	 * not an audit.</p>
	 *
	 * <p>Saved whatever the outcome. "No recording" and "a recording that did
	 * not survive the upload" look identical on a reviewer's screen otherwise,
	 * and only one of them is the candidate's doing. Never counts towards the
	 * proctoring warnings that fail an interview: a failed upload is the
	 * network, not conduct.</p>
	 *
	 * <p>Idempotent in the way that matters — the client retries this call
	 * until it lands, so duplicates are possible and are harmless. Two
	 * identical audit lines are a far better outcome than none.</p>
	 */
	@PostMapping("/{scheduleId}/recording-outcome")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Void> recordRecordingOutcome(@PathVariable Long scheduleId,
			@RequestBody RecordingOutcomeRequest request) {

		String kind = request.getKind() == null ? "recording" : request.getKind();
		String eventType = request.isSuccess() ? "recording_uploaded" : "recording_upload_failed";

		StringBuilder details = new StringBuilder();
		details.append(kind).append(" recording ");
		details.append(request.isSuccess() ? "saved" : "was not saved");
		details.append(" (").append(Math.round(request.getBytes() / 1024.0)).append(" KB");
		if (request.getParts() > 1) {
			details.append(", ").append(request.getParts()).append(" parts");
		}
		if (request.getAttempts() > 1) {
			// The attempt count is the signal that the candidate's connection
			// was struggling, which is worth knowing even when it succeeded in
			// the end.
			details.append(", ").append(request.getAttempts()).append(" attempts");
		}
		details.append(')');
		if (!request.isSuccess() && request.getFailureReason() != null && !request.getFailureReason().isBlank()) {
			details.append(": ").append(request.getFailureReason().trim());
		}

		try {
			proctoringEventRepository.save(ProctoringEvent.builder()
					.schedule(scheduleRepo.getReferenceById(scheduleId))
					.eventType(eventType)
					.details(details.toString())
					.build());
		} catch (Exception e) {
			// Reported, not thrown. The client is in its end-of-interview
			// flow and a failure to file the audit must not also cost the
			// candidate their completion.
			log.error("Failed to record the {} recording outcome for schedule {}", kind, scheduleId, e);
			return ResponseEntity.ok().build();
		}

		if (request.isSuccess()) {
			log.info("Schedule {}: {}", scheduleId, details);
		} else {
			log.warn("Schedule {}: {}", scheduleId, details);
		}
		return ResponseEntity.ok().build();
	}

	// Item 16: Get full conversation transcript for a schedule
	/**
	 * The full transcript of one interview, oldest turn first.
	 *
	 * <p>Projected rather than returned as entities: the entry's schedule is a
	 * lazy relation, and serialising it outside a transaction answered 500 for
	 * every interview — see {@link VoiceConversationEntryDTO}.</p>
	 */
	/**
	 * A playable link to one of an interview's recordings.
	 *
	 * <p>The schedule stores {@code s3://bucket/key}, which a browser cannot
	 * open — reviewers clicking "Camera" or "Shared screen" got a blank tab. A
	 * signed URL is returned rather than the bytes: a full interview's screen
	 * recording is far too large to pass through the application, and a video
	 * player needs range requests to seek.</p>
	 *
	 * @param kind {@code camera} for the webcam, {@code screen} for the shared screen
	 */
	@Autowired
	private com.rightpath.repository.InterviewRecordingUploadRepository recordingUploadRepository;

	@Autowired
	private com.rightpath.service.AudioTranscriptionService audioTranscriptionService;

	/** Whisper refuses anything over 25 MB; this leaves a margin for the multipart framing. */
	private static final long MAX_ANSWER_AUDIO_BYTES = 24L * 1024 * 1024;

	/**
	 * A stretch of the candidate's answer, to be turned into text.
	 *
	 * <p>Sent as a file over HTTP rather than as base64 over the socket. The socket caps a message at a megabyte,
	 * which forced the audio to be cut into four second pieces, so every answer was dozens of calls to the speech
	 * service and a fresh chance for it to invent words over a pause. A file here can be minutes long, so the
	 * browser cuts it at a natural pause and one answer is usually one call.</p>
	 *
	 * <p>The text comes back on the same topic as before, one message per chunk, with {@code done} set so the
	 * page knows this chunk has been dealt with even when it held no speech.</p>
	 */
	@PostMapping(value = "/voice/{scheduleId}/audio-chunk", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Void> audioChunk(@PathVariable Long scheduleId, @RequestParam("audio") MultipartFile audio,
			@RequestParam(value = "chunkIndex", defaultValue = "0") int chunkIndex,
			org.springframework.security.core.Authentication authentication) throws java.io.IOException {
		if (scheduleRepo.findByIdAndEmail(scheduleId, authentication.getName()).isEmpty()) {
			throw new org.springframework.security.access.AccessDeniedException(
					"Interview " + scheduleId + " is not assigned to the authenticated candidate");
		}
		if (audio.isEmpty()) {
			throw new IllegalArgumentException("audio is required");
		}
		if (audio.getSize() > MAX_ANSWER_AUDIO_BYTES) {
			throw new IllegalArgumentException("The audio is larger than " + (MAX_ANSWER_AUDIO_BYTES / (1024 * 1024)) + " MB");
		}
		audioTranscriptionService.transcribeAsync(scheduleId, audio.getBytes(), chunkIndex);
		return ResponseEntity.accepted().build();
	}

	@GetMapping("/{scheduleId}/recording")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<Map<String, Object>> getRecordingLink(@PathVariable Long scheduleId,
			@RequestParam(defaultValue = "camera") String kind,
			@RequestParam(defaultValue = "inline") String disposition,
			@RequestParam(defaultValue = "0") int part) {

		CandidateInterviewSchedule schedule = interviewService.getResultDetail(scheduleId);

		String recordingKind = "screen".equalsIgnoreCase(kind) ? "screen"
				: "mobile".equalsIgnoreCase(kind) ? "mobile" : "camera";
		boolean screen = recordingKind.equals("screen");
		String stored = screen ? schedule.getScreenRecordReferences()
				: recordingKind.equals("mobile") ? schedule.getMobileRecordReferences()
						: schedule.getRecordReferences();

		if (stored == null || stored.isBlank()) {
			// A 404 rather than an empty 200: "never recorded" is a different
			// thing from "here is a link to nothing", and the reviewer's screen
			// says which.
			throw new com.rightpath.exceptions.ResourceNotFoundException("No " + recordingKind
					+ " recording was stored for this interview.");
		}

		// A screen recording may be several files: sharing can stop and be
		// picked up again mid-interview, and each share is stored separately
		// because concatenated WebM plays only as far as the first boundary.
		// Older rows hold a single reference and split to a list of one, so
		// nothing recorded before this needs migrating.
		List<String> references = Arrays.stream(stored.split("\\R"))
				.map(String::trim)
				.filter(ref -> !ref.isEmpty())
				.toList();

		if (part < 0 || part >= references.size()) {
			throw new com.rightpath.exceptions.ResourceNotFoundException(
					"This interview has " + references.size() + " " + recordingKind
							+ " recording part(s); part " + (part + 1) + " does not exist.");
		}
		String reference = references.get(part);

		// The reference is only a string on a row; it says the upload was
		// *started*, not that anything landed. Signing without checking hands
		// the reviewer a player that cannot play and no reason why — the browser
		// reports "format not supported" whether the object is missing, empty or
		// genuinely corrupt. One HEAD turns all of that into a straight answer.
		long sizeBytes = storageService.objectSize(reference)
				.orElseThrow(() -> new com.rightpath.exceptions.ResourceNotFoundException(
						"The " + recordingKind + " recording for this interview was not saved. "
								+ "The upload did not complete, so there is nothing to play."));

		if (sizeBytes <= 0) {
			throw new com.rightpath.exceptions.ResourceNotFoundException(
					"The " + recordingKind + " recording for this interview is empty. "
							+ "The upload started but no data arrived.");
		}

		// Long enough to watch an hour-long interview through, short enough that
		// a copied link does not become a permanent public one.
		Duration ttl = Duration.ofHours(2);

		String url = "attachment".equalsIgnoreCase(disposition)
				? storageService.presignedDownloadUrl(reference,
						"interview-" + scheduleId + "-" + recordingKind
								+ (references.size() > 1 ? "-part" + (part + 1) : "") + ".webm", ttl)
				: storageService.presignedUrl(reference, ttl);

		// Size goes back with the link so the player can say how big the file is
		// — and so a suspiciously small recording is visible before someone
		// spends time deciding the player is broken.
		// `parts` goes back so the reviewer's player can offer the rest. Without
		// it a two-part recording looks like a short one that cuts off, and the
		// gap in the middle is exactly what a reviewer needs to notice.
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("url", url);
		body.put("expiresInSeconds", ttl.toSeconds());
		body.put("sizeBytes", sizeBytes);
		body.put("part", part);
		body.put("parts", references.size());

		// When this recording was made, so a reviewer can tell which sitting it is. The upload's own start is
		// the moment this file began; older recordings have no such row and fall back to the interview's start.
		java.time.LocalDateTime recordedAt = recordingUploadRepository.findFirstByStoredReference(reference)
				.map(upload -> upload.getStartedAt())
				.orElse(schedule.getStartedAt());
		if (recordedAt != null) {
			// Bare UTC with the zone said outright, so the browser shows the reader's own clock.
			body.put("recordedAt", recordedAt + "Z");
		}
		return ResponseEntity.ok(body);
	}

	@GetMapping("/{scheduleId}/conversation")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<List<VoiceConversationEntryDTO>> getConversation(@PathVariable Long scheduleId) {
		List<VoiceConversationEntryDTO> transcript = voiceConversationEntryRepository
				.findByInterviewScheduleIdOrderByTimestampAsc(scheduleId)
				.stream()
				.map(VoiceConversationEntryDTO::from)
				.toList();
		return ResponseEntity.ok(transcript);
	}

	// Item 16: Get interview stats
	@GetMapping("/stats")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<InterviewStatsDTO> getInterviewStats(@RequestParam(required = false) String jobPrefix,
			@RequestParam(required = false) InterviewRound round) {
		// Same call the results list makes, so the summary cards can never
		// describe a different set of interviews than the table under them.
		List<CandidateInterviewSchedule> all = interviewService.getResults(jobPrefix, round);

		long total = all.size();
		long completed = all.stream().filter(s -> s.getAttemptStatus() == AttemptStatus.COMPLETED).count();
		long passed = all.stream().filter(s -> s.getInterviewResult() == InterviewResult.PASSED).count();
		double passRate = completed > 0 ? (double) passed / completed * 100 : 0;

		double avgScore = all.stream()
				.filter(s -> s.getEvaluationJson() != null && !s.getEvaluationJson().isBlank())
				.mapToDouble(s -> {
					try {
						// Extract overallScore from JSON
						String json = s.getEvaluationJson();
						int idx = json.indexOf("\"overallScore\"");
						if (idx < 0) return 0;
						String after = json.substring(idx + 15);
						// find the number
						StringBuilder num = new StringBuilder();
						for (char c : after.toCharArray()) {
							if (Character.isDigit(c) || c == '.') num.append(c);
							else if (num.length() > 0) break;
						}
						return num.length() > 0 ? Double.parseDouble(num.toString()) : 0;
					} catch (Exception e) {
						return 0;
					}
				})
				.filter(score -> score > 0)
				.average()
				.orElse(0);

		double avgDuration = all.stream()
				.filter(s -> s.getStartedAt() != null && s.getEndedAt() != null)
				.mapToDouble(s -> Duration.between(s.getStartedAt(), s.getEndedAt()).toMinutes())
				.average()
				.orElse(0);

		return ResponseEntity.ok(InterviewStatsDTO.builder()
				.totalInterviews(total)
				.passRate(Math.round(passRate * 10.0) / 10.0)
				.avgScore(Math.round(avgScore * 10.0) / 10.0)
				.avgDurationMinutes(Math.round(avgDuration * 10.0) / 10.0)
				.build());
	}
	
	@PostMapping("/verification-session")
	public ResponseEntity<?> createVerificationSession() {

	    String sessionId = roomVerificationService.createSession();

	    return ResponseEntity.ok(
	            Map.of("sessionId", sessionId)
	    );	
	}

	@PostMapping(value = "/verify-room", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<?> verifyRoom(
	        @RequestParam String sessionId,
	        @RequestParam MultipartFile image
	) {

	    roomVerificationService.verifyRoom(sessionId, image);

	    return ResponseEntity.ok().build();
	}

	@GetMapping("/verification-status")
	public ResponseEntity<?> verificationStatus(@RequestParam String sessionId) {

	    RoomVerificationSession session =
	            roomVerificationService.getStatus(sessionId);

	    return ResponseEntity.ok(
	            Map.of(
	                    "status", session.getStatus(),
	                    "reason", session.getReason()
	            )
	    );
	}
	
	@PostMapping(value = "/upload-interview-questions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> uploadInterviewQuestions(
	        @RequestParam String jobPrefix,
	        @RequestParam("file") MultipartFile file) {

	    interviewQuestionsService.uploadInterviewQuestions(jobPrefix, file);

	    return ResponseEntity.ok(
	            Map.of("message", "Interview questions uploaded successfully")
	    );
	}
	

	@GetMapping("/questions")
	@PreAuthorize("hasAuthority('INTERVIEW_START')")
	public ResponseEntity<?> getInterviewQuestions(@RequestParam String jobPrefix) {

	    String data = interviewQuestionsService.fetchInterviewQuestions(jobPrefix);

	    return ResponseEntity.ok(data);
	}

	
	
	@PostMapping("/update-questions/{jobPrefix}")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<?> updateInterviewQuestions(
	        @PathVariable String jobPrefix,
	        @RequestParam(required = false) List<String> categories,
	        @RequestParam(required = false) Integer totalQuestions) {

	    if (categories == null || categories.isEmpty()) {
	        categories = List.of("OOP", "ExceptionHandling", "Multithreading", "Collections",
	                "Java8", "JVM", "I_O", "Generics", "DesignPatterns", "JDBC");
	    }
	    if (totalQuestions == null || totalQuestions <= 0) {
	        totalQuestions = 100;
	    }
	    interviewQuestionsService.updateInterviewQuestionsWithAI(jobPrefix, categories, totalQuestions);
	    return ResponseEntity.ok(Map.of("message", "Interview questions updated successfully"));
	}

	/**
	 * What a person decided about this interview, if anyone has looked at it.
	 *
	 * <p>204 rather than an empty object when nobody has: "not yet reviewed"
	 * and "reviewed, with nothing to say" are different states, and a screen
	 * showing the second when it means the first tells a reviewer their
	 * colleague has already been through it.</p>
	 */
	@GetMapping("/{scheduleId}/review")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<InterviewReviewDTO> getReview(@PathVariable Long scheduleId) {
		return interviewReviewService.find(scheduleId)
				.map(InterviewReviewDTO::from)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	/**
	 * Records a reviewer's notes, and their result if they are overturning it.
	 *
	 * <p>Behind INTERVIEW_REVIEW rather than the INTERVIEW_ASSIGN that guards
	 * reading: changing a finished result is the one action here that alters a
	 * hiring outcome after the fact, and it should be grantable separately from
	 * booking interviews and reading their scores.</p>
	 *
	 * <p>The reviewer is taken from the authenticated principal. A client-supplied
	 * author on a record of who overturned a decision records whoever the client
	 * said it was.</p>
	 */
	@PostMapping("/{scheduleId}/review")
	@PreAuthorize("hasAuthority('INTERVIEW_REVIEW')")
	public ResponseEntity<InterviewReviewDTO> saveReview(
			@PathVariable Long scheduleId,
			@RequestBody InterviewReviewRequest request) {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		String reviewer = (authentication == null || authentication.getName() == null)
				? "system"
				: authentication.getName();

		InterviewReview saved = interviewReviewService.save(
				scheduleId,
				reviewer,
				request.getNotes(),
				request.getOverriddenResult(),
				request.getOverrideReason());

		return ResponseEntity.ok(InterviewReviewDTO.from(saved));
	}
}
