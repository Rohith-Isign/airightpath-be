package com.rightpath.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.CandidateInterviewSchedule;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CandidateInterviewScheduleDTO {

	private Long id;
	private String jobPrefix;
	private String email;
	private String attemptStatus; // attempted | not_attempted
	private String interviewResult; // passed | failed | pending
	private LocalDateTime assignedAt;
	private LocalDateTime deadlineTime;
	private String recordReferences;
	/**
	 * Where the candidate's shared screen was stored.
	 *
	 * The upload has always worked and the column has always been written, but
	 * this field was missing from the DTO — so the recording sat in storage with
	 * no way for a reviewer to reach it. For a coding round it is the only
	 * evidence of how the answer was arrived at.
	 */
	private String screenRecordReferences;

	/** The paired phone's recording, one reference per line. */
	private String mobileRecordReferences;
	private String summaryReferences;
	private int warningCount;
	private String evaluationJson;

	/**
	 * The same evaluation, parsed.
	 *
	 * <p>Only the raw JSON string was sent, while every screen reads
	 * {@code evaluation.overallScore} and {@code evaluation.recommendation} —
	 * so the score and recommendation columns rendered "--" on every row of
	 * every results list, and the CSV export wrote an empty score for every
	 * candidate. The numbers were in the payload the whole time, one
	 * {@code JSON.parse} away, under a name nothing looked at.</p>
	 *
	 * <p>Parsed here rather than in the browser so there is one place that
	 * knows the shape and one place that copes with a malformed blob. Null
	 * where the interview has not been graded, or where the stored JSON cannot
	 * be read.</p>
	 */
	private VoiceEvaluationResult evaluation;
	private LocalDateTime startedAt;
	private LocalDateTime endedAt;

	/**
	 * How the interview ended.
	 *
	 * <p>Recorded on the row since timeouts and proctoring violations were
	 * introduced, and never once sent to the browser — so the results list
	 * showed "--" in its completion column and the detail screen said "how
	 * this interview ended was not recorded", for interviews whose reason was
	 * sitting in the database the whole time.</p>
	 */
	private String completionReason;

	/**
	 * Whether the AI's verdict should be looked at before it is acted on.
	 *
	 * <p>Read from the schedule's own column rather than out of
	 * {@link #evaluationJson}, so a list of fifty results does not deserialise
	 * fifty LONGTEXT blobs to find out which of them are marked.</p>
	 */
	private boolean needsHumanReview;

	/**
	 * The result a person imposed, or null where the AI's stands.
	 *
	 * <p>Kept beside {@link #interviewResult} rather than replacing it. A screen
	 * that shows only the final answer cannot say the machine was overruled,
	 * which is the part a reviewer opening it next actually needs to know.</p>
	 */
	private String overriddenResult;

	/** Who overturned it. Null where nobody did. */
	private String overriddenBy;
	   private LocalDate questionsFromDate;
	    private LocalDate questionsToDate;

	/**
	 * Which interview this was — {@code L2_TECHNICAL} or {@code L3_BEHAVIORAL}.
	 *
	 * <p>Always populated, including for schedules written before the column
	 * existed: those read as the default round rather than null, so a results
	 * screen filtering by round does not hide historic interviews.</p>
	 */
	private String round;

	/** Human label for the round, so clients need not map the enum. */
	private String roundLabel;

	/**
	 * The removal audit: when the result was taken off the list, by whom and
	 * why. All three are null while the result stands.
	 *
	 * <p>Carried on the DTO rather than fetched separately because a removed
	 * result is only ever shown with its reason attached — a row marked
	 * "removed" and nothing else is the state this replaced.</p>
	 */
	private LocalDateTime deletedAt;
	private String deletedBy;
	private String deleteReason;

	public CandidateInterviewScheduleDTO(CandidateInterviewSchedule entity) {
		this.round = entity.getEffectiveRound().name();
		this.roundLabel = entity.getEffectiveRound().getDisplayName();
		this.id = entity.getId();
		this.jobPrefix = entity.getJobPrefix();
		this.email = entity.getEmail();
		this.attemptStatus = entity.getAttemptStatus().toString();
		this.interviewResult = entity.getInterviewResult().toString();
		this.assignedAt = entity.getAssignedAt();
		this.deadlineTime = entity.getDeadlineTime();
		this.recordReferences = entity.getRecordReferences();
		this.screenRecordReferences = entity.getScreenRecordReferences();
		this.mobileRecordReferences = entity.getMobileRecordReferences();
		this.summaryReferences = entity.getSummaryReferences();
		this.warningCount = entity.getWarningCount();
		this.evaluationJson = entity.getEvaluationJson();
		this.evaluation = parseEvaluation(entity.getEvaluationJson());
		this.startedAt = entity.getStartedAt();
		this.endedAt = entity.getEndedAt();
		this.completionReason = entity.getCompletionReason() != null
				? entity.getCompletionReason().name()
				: null;
		this.needsHumanReview = entity.isNeedsHumanReview();
		this.deletedAt = entity.getDeletedAt();
		this.deletedBy = entity.getDeletedBy();
		this.deleteReason = entity.getDeleteReason();
	}

	/**
	 * Shared, because {@code ObjectMapper} is thread-safe once configured and
	 * building one per row of a results list is the expensive part.
	 */
	private static final com.fasterxml.jackson.databind.ObjectMapper EVALUATION_MAPPER =
			new com.fasterxml.jackson.databind.ObjectMapper()
					.configure(com.fasterxml.jackson.databind.DeserializationFeature
							.FAIL_ON_UNKNOWN_PROPERTIES, false);

	/**
	 * The stored evaluation, or null if it cannot be read.
	 *
	 * <p>Never throws. A single unparseable blob — hand-edited, truncated, or
	 * written by an older version — must not take down the whole results list
	 * with it; that row simply shows no score, which is what it showed before
	 * any of this worked anyway.</p>
	 */
	private static VoiceEvaluationResult parseEvaluation(String json) {
		if (json == null || json.isBlank()) {
			return null;
		}
		try {
			return EVALUATION_MAPPER.readValue(json, VoiceEvaluationResult.class);
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * The same DTO with a reviewer's decision attached.
	 *
	 * <p>A second constructor rather than a lookup inside the first: the entity
	 * does not know about its review, and having the DTO fetch one per row
	 * would put a query per result behind every list that renders one.</p>
	 */
	public CandidateInterviewScheduleDTO(CandidateInterviewSchedule entity,
			com.rightpath.entity.InterviewReview review) {
		this(entity);
		if (review != null && review.getOverriddenResult() != null) {
			this.overriddenResult = review.getOverriddenResult().name();
			this.overriddenBy = review.getReviewerEmail();
		}
	}
}
