package com.rightpath.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewReview;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.CompletionReason;
import com.rightpath.enums.InterviewResult;
import com.rightpath.enums.InterviewRound;

/**
 * What the admin screens actually receive about an interview.
 *
 * <p>Three separate bugs lived in this mapping, and every one of them showed
 * up as something missing or nonsense on a reviewer's screen rather than as an
 * error anyone could search for:</p>
 *
 * <ul>
 *   <li>The evaluation was sent only as a raw JSON string, while every screen
 *       read {@code evaluation.overallScore} — so score and recommendation
 *       rendered "--" on every row of every results list, and the CSV export
 *       wrote an empty score for every candidate.</li>
 *   <li>{@code completionReason} was recorded on the row and never sent at
 *       all, so interviews that had plainly timed out reported "how this
 *       interview ended was not recorded".</li>
 *   <li>A JSON null recommendation came back through Jackson as the
 *       <em>string</em> "null" and was rendered as a badge reading null.</li>
 * </ul>
 */
class InterviewScheduleDtoMappingTest {

    private CandidateInterviewSchedule schedule(String evaluationJson) {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setId(31L);
        schedule.setJobPrefix("INTERVIEW-054");
        schedule.setEmail("candidate@example.test");
        schedule.setAttemptStatus(AttemptStatus.COMPLETED);
        schedule.setInterviewResult(InterviewResult.PENDING);
        schedule.setRound(InterviewRound.L2_TECHNICAL);
        schedule.setStartedAt(LocalDateTime.now().minusMinutes(60));
        schedule.setEndedAt(LocalDateTime.now());
        schedule.setEvaluationJson(evaluationJson);
        return schedule;
    }

    @Test
    @DisplayName("the stored evaluation is parsed, not just forwarded as text")
    void evaluationIsParsed() {
        CandidateInterviewScheduleDTO dto = new CandidateInterviewScheduleDTO(
                schedule("{\"overallScore\":7.5,\"recommendation\":\"HIRE\",\"summary\":\"Solid.\"}"));

        assertThat(dto.getEvaluation()).isNotNull();
        assertThat(dto.getEvaluation().getOverallScore()).isEqualTo(7.5);
        assertThat(dto.getEvaluation().getRecommendation()).isEqualTo("HIRE");
        // The raw text stays available for anything that wants it.
        assertThat(dto.getEvaluationJson()).contains("overallScore");
    }

    @Test
    @DisplayName("a JSON null recommendation stays null, never the string \"null\"")
    void nullRecommendationDoesNotBecomeTheWordNull() {
        // An interview nobody answered is recorded as not assessable, with the
        // recommendation deliberately unset. That reached a reviewer's screen
        // as a badge reading "null".
        CandidateInterviewScheduleDTO dto = new CandidateInterviewScheduleDTO(
                schedule("{\"overallScore\":0,\"recommendation\":null,\"summary\":null}"));

        assertThat(dto.getEvaluation()).isNotNull();
        assertThat(dto.getEvaluation().getRecommendation()).isNull();
        assertThat(dto.getEvaluation().getSummary()).isNull();
    }

    @Test
    @DisplayName("how the interview ended is sent to the browser")
    void completionReasonIsSent() {
        CandidateInterviewSchedule entity = schedule(null);
        entity.setCompletionReason(CompletionReason.TIMEOUT);

        assertThat(new CandidateInterviewScheduleDTO(entity).getCompletionReason())
                .isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("an interview with no reason recorded says so by omission")
    void missingCompletionReasonIsNull() {
        assertThat(new CandidateInterviewScheduleDTO(schedule(null)).getCompletionReason()).isNull();
    }

    @Test
    @DisplayName("an unreadable evaluation costs that row its score, not the whole list")
    void malformedEvaluationDoesNotThrow() {
        // Hand-edited, truncated, or written by an older version. A results
        // list of fifty must not 500 because one row cannot be parsed.
        assertThatCode(() -> {
            CandidateInterviewScheduleDTO dto =
                    new CandidateInterviewScheduleDTO(schedule("{not json at all"));
            assertThat(dto.getEvaluation()).isNull();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an ungraded interview simply has no evaluation")
    void noEvaluationYet() {
        assertThat(new CandidateInterviewScheduleDTO(schedule(null)).getEvaluation()).isNull();
        assertThat(new CandidateInterviewScheduleDTO(schedule("  ")).getEvaluation()).isNull();
    }

    @Test
    @DisplayName("an override is carried alongside the AI's own result, not instead of it")
    void overrideDoesNotHideTheAiResult() {
        CandidateInterviewSchedule entity = schedule(null);
        entity.setInterviewResult(InterviewResult.FAILED);

        InterviewReview review = InterviewReview.builder()
                .interviewScheduleId(31L)
                .reviewerEmail("reviewer@example.test")
                .overriddenResult(InterviewResult.PASSED)
                .overrideReason("Connection dropped twice; not the candidate's doing.")
                .build();

        CandidateInterviewScheduleDTO dto = new CandidateInterviewScheduleDTO(entity, review);

        assertThat(dto.getOverriddenResult()).isEqualTo("PASSED");
        assertThat(dto.getOverriddenBy()).isEqualTo("reviewer@example.test");
        // Still there: a screen showing only the final answer cannot say the
        // machine was overruled, which is what the next reviewer needs to know.
        assertThat(dto.getInterviewResult()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("a review with no override leaves the AI's result standing")
    void notesOnlyReviewIsNotAnOverride() {
        InterviewReview review = InterviewReview.builder()
                .interviewScheduleId(31L)
                .reviewerEmail("reviewer@example.test")
                .notes("Agreed with the assessment.")
                .build();

        CandidateInterviewScheduleDTO dto = new CandidateInterviewScheduleDTO(schedule(null), review);

        assertThat(dto.getOverriddenResult()).isNull();
        assertThat(dto.getOverriddenBy()).isNull();
    }
}
