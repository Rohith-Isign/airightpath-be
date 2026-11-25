package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;

/**
 * Re-evaluating the same interview came back with a different score each time: the grader wrote a new list of role
 * requirements on every run and judged each afresh. The list is now fixed per job, and each requirement is marked from
 * the ratings its answers were given, so the same interview gives the same score.
 */
class ConsistentReevaluationTest {

    private static VoiceEvaluationResult.Concept req(String name, String importance) {
        return VoiceEvaluationResult.Concept.builder().concept(name).requirement("SKILL").skill("Java")
                .importance(importance).area("BACKEND").build();
    }

    private static VoiceEvaluationResult.Concept marked(String name, String status, Integer... questions) {
        return VoiceEvaluationResult.Concept.builder().concept(name).status(status)
                .questionNumbers(List.of(questions)).build();
    }

    private final List<VoiceConversationEntry> entries = new ArrayList<>();

    private void qa(String answer, Integer rating) {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content("q").build());
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content(answer)
                .answerScore(rating).build());
    }

    @Test
    void theJobsListStaysTheSameWhateverTheGraderReturns() {
        List<VoiceEvaluationResult.Concept> fixed = List.of(req("Java collections", "CORE"),
                req("Spring Boot REST APIs", "CORE"), req("Unit testing", "SUPPORTING"));
        VoiceEvaluationResult r = new VoiceEvaluationResult();
        // The grader reworded one, dropped one and added one of its own.
        r.setExpectedConcepts(new ArrayList<>(List.of(marked("Java Collections", "CORRECT", 2),
                marked("Kubernetes", "PARTIAL", 3))));
        InterviewEvaluationService.alignToRequirements(r, fixed);

        assertThat(r.getExpectedConcepts()).extracting(VoiceEvaluationResult.Concept::getConcept)
                .containsExactly("Java collections", "Spring Boot REST APIs", "Unit testing");
        assertThat(r.getExpectedConcepts().get(0).getStatus()).isEqualTo("CORRECT");
        assertThat(r.getExpectedConcepts().get(1).getStatus()).isEqualTo("NOT_ASKED");
        assertThat(r.getExpectedConcepts().get(0).getImportance()).isEqualTo("CORE");
    }

    @Test
    void eachRequirementIsMarkedFromTheRatingsOfItsAnswers() {
        qa("intro", null);   // Q1, not rated
        qa("good", 8);       // Q2
        qa("partial", 5);    // Q3
        qa("[NO RESPONSE - SKIPPED]", null); // Q4, skipped counts 0
        VoiceEvaluationResult r = new VoiceEvaluationResult();
        r.setExpectedConcepts(new ArrayList<>(List.of(
                marked("a", "PARTIAL", 2),          // 8 -> shown, whatever the grader said
                marked("b", "CORRECT", 3),          // 5 -> partly
                marked("c", "PARTIAL", 4),          // skipped -> not shown
                marked("d", "CORRECT"),             // no question -> not reached
                marked("e", "PARTIAL", 1))));       // intro only, unrated -> grader's mark stays
        InterviewEvaluationService.markFromRatings(r, entries);

        assertThat(r.getExpectedConcepts()).extracting(VoiceEvaluationResult.Concept::getStatus)
                .containsExactly("CORRECT", "PARTIAL", "INCORRECT", "NOT_ASKED", "PARTIAL");
    }

    @Test
    void aReviewersRatingIsTheOneUsed() {
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.INTERVIEWER).content("q").build());
        entries.add(VoiceConversationEntry.builder().role(ConversationRole.CANDIDATE).content("a").answerScore(3)
                .reviewedScore(8).build());
        assertThat(InterviewEvaluationService.ratingsByQuestion(entries)).containsEntry(1, 8.0);
    }
}
