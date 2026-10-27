package com.rightpath.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.CompletionReason;
import com.rightpath.enums.InterviewPhase;
import com.rightpath.enums.InterviewRound;
import com.rightpath.enums.InterviewResult;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
// Only the columns a transaction actually changed are written. By default
// Hibernate writes every column from the copy it loaded, so a request that
// held an interview open for a while — a 100 MB recording on its way to S3 —
// wrote its stale copy of everything else back over whatever had changed
// meanwhile: the interview's COMPLETED status, its evaluation, the other
// recording's reference.
@org.hibernate.annotations.DynamicUpdate
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CandidateInterviewSchedule {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "job_prefix")
	private String jobPrefix;

	private String email;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "email", referencedColumnName = "email", insertable = false, updatable = false)
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private Users user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "job_prefix", referencedColumnName = "job_prefix", insertable = false, updatable = false)
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private JobPost jobPost;
	
	@Column(name = "questions_from_date")
	private LocalDateTime questionsFromDate;

	@Column(name = "questions_to_date")
	private LocalDateTime questionsToDate;

	@Enumerated(EnumType.STRING)
	private AttemptStatus attemptStatus;

	@Enumerated(EnumType.STRING)
	private InterviewResult interviewResult;

	@Column(columnDefinition = "TEXT")
	private String recordReferences;

	@Column(columnDefinition = "TEXT")
	private String screenRecordReferences;

	/**
	 * The recording of the candidate's paired phone, as a newline-separated
	 * list of parts like the other two. A new column: {@code ddl-auto: update}
	 * adds it on its own, and rows from before it exist read as null — no phone
	 * recording, which is what they have.
	 */
	@Column(columnDefinition = "TEXT")
	private String mobileRecordReferences;

	@Column(name = "summery_references", columnDefinition = "TEXT")
	private String summaryReferences;

	private LocalDateTime assignedAt;

	private LocalDateTime deadlineTime;

	// Keep column for DB compatibility but no longer driven by backend logic
	@Enumerated(EnumType.STRING)
	@Builder.Default
	private InterviewPhase currentPhase = InterviewPhase.INTRODUCTION;

	/**
	 * Which interview this is — L2 technical or L3 behavioural.
	 *
	 * <p>Null on every schedule created before rounds existed. Read it through
	 * {@link #getEffectiveRound()} rather than directly: those rows are technical
	 * interviews, and treating null as "unknown" would drop historic results out
	 * of both round filters.</p>
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "interview_round", length = 32)
	private InterviewRound round;

	@Builder.Default
	private int difficultyLevel = 2;

	@Builder.Default
	private int questionsAskedInPhase = 0;

	@Builder.Default
	private int totalQuestionsAsked = 0;

	/**
	 * Turns that opened new ground, as opposed to pressing on the last one.
	 *
	 * <p>{@link #totalQuestionsAsked} counts every interviewer turn and is what
	 * the hard ceiling is enforced against — it has to, or an interview could be
	 * stretched indefinitely by calling each turn a follow-up. But the floor is a
	 * statement about evidence, not about talking: ten turns that were all
	 * follow-ups on one question is not ten questions' worth of signal. The
	 * minimum is therefore measured here, and the maximum there.</p>
	 */
	@Builder.Default
	private int distinctQuestionsAsked = 0;

	/**
	 * Follow-ups and rephrases spent on the question currently open.
	 *
	 * <p>Reset the moment a new question is asked. Without a counter the model
	 * can keep deciding one more probe would help, and a candidate is held on the
	 * same question until the ceiling stops the interview — the failure the
	 * budget in {@code InterviewConductPolicy} exists to prevent.</p>
	 */
	@Builder.Default
	private int followUpsOnCurrentQuestion = 0;

	@Builder.Default
	private int rephrasesOnCurrentQuestion = 0;

	@Column(columnDefinition = "TEXT")
	private String runningSummary;

	@Builder.Default
	private int warningCount = 0;

	@Column(columnDefinition = "LONGTEXT")
	private String evaluationJson;

	/**
	 * Whether this result should be looked at by a person before it is acted on.
	 *
	 * <p>Duplicated out of {@link #evaluationJson}, where the reasons also live.
	 * The results list needs to mark and filter on it, and doing that from the
	 * JSON means deserialising a LONGTEXT column for every row on the page.</p>
	 */
	@Builder.Default
	private boolean needsHumanReview = false;

	@Column(columnDefinition = "VARCHAR(255) DEFAULT 'Sarah'")
	@Builder.Default
	private String interviewerName = "Sarah";

	public String getInterviewerName() {
		return interviewerName != null && !interviewerName.isBlank() ? interviewerName : "Sarah";
	}

	@Enumerated(EnumType.STRING)
	private CompletionReason completionReason;

	private LocalDateTime startedAt;
	private LocalDateTime endedAt;

	/**
	 * When this result was removed from the results list; null while it stands.
	 *
	 * <p>Removal is soft, for the same reason a job posting's is: the
	 * transcript, the proctoring events, the recordings and the evaluation
	 * under this row are the evidence behind a hiring decision, and a hard
	 * delete would destroy them along with any way of asking later what was
	 * removed or by whom. The row survives, drops out of the default listing,
	 * and stays readable to anyone who asks for removed results explicitly.</p>
	 *
	 * <p>{@code deletedAt IS NULL} is the liveness test.</p>
	 */
	private LocalDateTime deletedAt;

	/** Email of the admin who removed this result; null while it stands. */
	private String deletedBy;

	/**
	 * Why it was removed. Required — the endpoint rejects a blank one.
	 *
	 * <p>A result that vanished with no reason is the kind of thing that gets
	 * asked about months later, when nobody remembers whether it was a test
	 * run, a duplicate or a candidate who asked to be withdrawn.</p>
	 */
	@Column(columnDefinition = "TEXT")
	private String deleteReason;

	/** True while this result still counts. */
	public boolean isDeleted() {
		return deletedAt != null;
	}

	@OneToMany(mappedBy = "interviewSchedule", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private List<VoiceConversationEntry> conversationEntries = new ArrayList<>();

	@OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
	@Builder.Default
	@ToString.Exclude
	@EqualsAndHashCode.Exclude
	private List<ProctoringEvent> proctoringEvents = new ArrayList<>();

	public void incrementQuestionsAsked() {
		this.totalQuestionsAsked++;
	}

	/** A turn that moved on: counts against the floor and clears the probe budget. */
	public void recordNewQuestion() {
		this.distinctQuestionsAsked++;
		this.followUpsOnCurrentQuestion = 0;
		this.rephrasesOnCurrentQuestion = 0;
	}

	public void recordFollowUp() {
		this.followUpsOnCurrentQuestion++;
	}

	public void recordRephrase() {
		this.rephrasesOnCurrentQuestion++;
	}

	/**
	 * Questions asked, for anything that needs the floor rather than the ceiling.
	 *
	 * <p>Falls back to the turn count for interviews that ran before turns were
	 * classified: their rows carry a zero here, and reading that literally would
	 * tell the model a finished interview had not started.</p>
	 */
	public int getEffectiveDistinctQuestions() {
		return distinctQuestionsAsked > 0 ? distinctQuestionsAsked : totalQuestionsAsked;
	}

	public void addWarning() {
		this.warningCount++;
	}

	/** Never null — a schedule with no round recorded is a technical interview. */
	public InterviewRound getEffectiveRound() {
		return InterviewRound.orDefault(round);
	}
}
