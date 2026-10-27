package com.rightpath.service;

import com.rightpath.dto.voice.ResumeResponse;
import com.rightpath.dto.voice.VoiceAnswerRequest;
import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.dto.voice.VoiceSessionStatus;
import com.rightpath.dto.voice.VoiceStartResponse;

public interface VoiceInterviewService {

    VoiceStartResponse startVoiceInterview(String jobPrefix, String email);

    void processVoiceAnswer(Long scheduleId, VoiceAnswerRequest request);

    void endVoiceInterview(Long scheduleId);

    /** Ends the interview for a reason other than the candidate pressing end. No-op if already over. */
    void endVoiceInterview(Long scheduleId, com.rightpath.enums.CompletionReason reason);

    boolean handleWarning(Long scheduleId);

    VoiceSessionStatus getSessionStatus(Long scheduleId);

    VoiceEvaluationResult getEvaluation(Long scheduleId);

    ResumeResponse resumeInterview(Long scheduleId);

	VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate);

	/**
	 * Start one specific booked interview.
	 *
	 * @param scheduleId the interview the candidate chose, or null to fall back
	 *                   to their most recently assigned one on this job
	 */
	VoiceStartResponse startVoiceInterview(String jobPrefix, String email, Long fromDate, Long toDate,
			Long scheduleId);
}
