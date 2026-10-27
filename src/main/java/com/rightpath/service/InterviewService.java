package com.rightpath.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.StartInterviewResponse;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.InterviewResult;

public interface InterviewService {

//	CandidateInterviewSchedule assignInterview(String jobPrefix, String email, LocalDateTime assignedAt,
//			LocalDateTime deadlineTime);
//
//	List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, java.util.List<String> emails,
//			LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail);

	List<CandidateInterviewSchedule> getActiveInterviewsByEmail(String email);

	String storeRecording(Long interviewScheduleId, MultipartFile videoFile);

	String storeScreenRecording(Long interviewScheduleId, MultipartFile screenFile);

	/**
	 * Start sending a recording in pieces.
	 *
	 * @param kind {@code camera} or {@code screen}
	 * @return {@code uploadId} and {@code blobName}, both to be quoted on every later call
	 */
	java.util.Map<String, String> beginRecordingUpload(Long interviewScheduleId, String kind);

	/**
	 * As above, naming the video type. A phone's browser may record MP4 rather
	 * than WebM, and a file stored under the wrong type does not play.
	 *
	 * @param contentType {@code video/webm} or {@code video/mp4}; anything else, or null, means WebM
	 */
	java.util.Map<String, String> beginRecordingUpload(Long interviewScheduleId, String kind, String contentType);

	/** Store one piece of a recording that is being sent in pieces. */
	void uploadRecordingPart(Long interviewScheduleId, String blobName, String uploadId, int part, byte[] data);

	/** Join the pieces, and add the finished recording to the interview's list. */
	String completeRecordingUpload(Long interviewScheduleId, String kind, String blobName, String uploadId);

	StartInterviewResponse start(String jobPrefix, String email, String resumeSummary);

//	String answer(Long interviewScheduleId, String conversationHistory, boolean finalAnswer, String jobPrefix);

	void markCompleted(Long interviewScheduleId, AttemptStatus attemptStatus, InterviewResult interviewResult,
			String summaryRef);

	/**
	 * Every interview on a job, or every interview there is when no job is given.
	 *
	 * @deprecated retained for callers that genuinely want all rounds; prefer the
	 *             overload, which makes the round explicit rather than implied.
	 */
	@Deprecated
	List<CandidateInterviewSchedule> getResults(String jobPrefix);

	/**
	 * Interviews on a job, optionally narrowed to one round.
	 *
	 * <p>Matching is on the <em>effective</em> round, so schedules written before
	 * the column existed count as {@link com.rightpath.enums.InterviewRound#DEFAULT}
	 * — the same reading the DTO reports. Filtering on the stored column instead
	 * would make historic interviews vanish from both rounds.</p>
	 *
	 * @param round the round to keep, or null for all rounds
	 */
	List<CandidateInterviewSchedule> getResults(String jobPrefix, com.rightpath.enums.InterviewRound round);

	/**
	 * Interviews on a job, optionally narrowed to one round, optionally
	 * including results an admin has removed.
	 *
	 * <p>Removed results are excluded by default. They are not gone — removal
	 * is soft and audited — but a results screen that still lists them is a
	 * results screen that was not cleaned up.</p>
	 *
	 * @param includeDeleted true to list removed results alongside live ones
	 */
	List<CandidateInterviewSchedule> getResults(String jobPrefix, com.rightpath.enums.InterviewRound round,
			boolean includeDeleted);

	CandidateInterviewSchedule getResultDetail(Long id);

	/**
	 * Remove a finished interview's result from the listing, with a reason.
	 *
	 * <p>Soft: the row, its transcript, its proctoring events and its
	 * recordings all survive, and who removed it and why are recorded on it.
	 * Removing an already-removed result is rejected rather than silently
	 * overwriting the first reason.</p>
	 *
	 * @param reason why it is being removed; required and non-blank
	 * @return the row as it now stands
	 */
	CandidateInterviewSchedule deleteResult(Long id, String reason);

	/**
	 * Put a removed result back, with a reason recorded in the log.
	 *
	 * <p>The counterpart to {@link #deleteResult}: removal is an admin action
	 * taken in a hurry on a live screen, and a mistake must not be permanent.</p>
	 */
	CandidateInterviewSchedule restoreResult(Long id);

	String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId);

	/**
	 * @deprecated superseded by the overload taking {@code codeOutput}; a coding
	 *             answer graded without its run output is graded on source alone.
	 */
	@Deprecated
	String answer(Long interviewScheduleId, String answerText, boolean finalAnswer, String jobPrefix,
			String codeContent, String codeLanguage);

	/**
	 * Processes one candidate answer and returns the interviewer's next message.
	 *
	 * @param codeOutput what the candidate's code printed when they ran it, or
	 *                   null if they never did. Passed on to the model so a
	 *                   coding answer is judged on behaviour, not only on source.
	 */
	String answer(Long interviewScheduleId, String answerText, boolean finalAnswer, String jobPrefix,
			String codeContent, String codeLanguage, String codeOutput);

//	String prepareQuestionsAndCreateSession(String jobPrefix, String email, Long scheduleId, Long fromDate,
//			Long toDate);

//	List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, List<String> emails,
//			LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail, LocalDateTime questionsFromDate,
//			LocalDateTime questionsToDate);
//
//	CandidateInterviewSchedule assignInterview(String jobPrefix, String email, LocalDateTime assignedAt,
//			LocalDateTime deadlineTime, LocalDateTime questionsFromDate, LocalDateTime questionsToDate);
	
	  CandidateInterviewSchedule assignInterview(String jobPrefix, String email, LocalDateTime assignedAt,
	            LocalDateTime deadlineTime, LocalDate questionsFromDate, LocalDate questionsToDate);
	    
	    /**
	     * @deprecated superseded by the overload taking an
	     *             {@link com.rightpath.enums.InterviewRound}; this one books
	     *             the default round.
	     */
	    @Deprecated
	    List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, List<String> emails,
	            LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail,
	            LocalDate questionsFromDate, LocalDate questionsToDate);

	    /**
	     * Books one interview round for each of the given candidates.
	     *
	     * @param round which interview to book; null means the default round, so a
	     *              caller written before rounds existed keeps its old behaviour
	     */
	    List<CandidateInterviewSchedule> assignInterviewBulk(String jobPrefix, List<String> emails,
	            LocalDateTime assignedAt, LocalDateTime deadlineTime, boolean sendEmail,
	            LocalDate questionsFromDate, LocalDate questionsToDate,
	            com.rightpath.enums.InterviewRound round);

}