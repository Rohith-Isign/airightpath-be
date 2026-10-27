package com.rightpath.dto;

import java.time.LocalDateTime;

/** Progress of reading stored papers into the bank. */
public record QuestionBankSyncStatus(boolean running, int processed, int total, int newQuestions,
		int newAssignments, int failed, LocalDateTime startedAt, LocalDateTime finishedAt, String message) {

	public static QuestionBankSyncStatus idle() {
		return new QuestionBankSyncStatus(false, 0, 0, 0, 0, 0, null, null, "Not synced yet");
	}
}
