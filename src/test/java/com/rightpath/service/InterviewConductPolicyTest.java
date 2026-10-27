package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.InterviewDifficulty;
import com.rightpath.util.PromptInjectionGuard;

/**
 * The rules that stop a dynamic interview from running forever or stopping short.
 *
 * <p>Questions are written by the model now, not read from a list, so nothing
 * about the interview's length is implied by its content — the floor and the
 * ceiling are the only things that decide when it ends. Both are asserted here
 * because either one failing is invisible until a real candidate is affected: a
 * broken ceiling means an interview that never closes, and a broken floor means
 * one scored on two answers.</p>
 */
class InterviewConductPolicyTest {

    private static final int MIN = 10;
    private static final int MAX = 20;

    private InterviewConductPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new InterviewConductPolicy(new PromptInjectionGuard());
        ReflectionTestUtils.setField(policy, "minQuestions", MIN);
        ReflectionTestUtils.setField(policy, "maxQuestions", MAX);
        ReflectionTestUtils.setField(policy, "maxFollowUpsPerQuestion", 2);
        ReflectionTestUtils.setField(policy, "maxRephrasesPerQuestion", 1);
    }

    /**
     * An interview that has taken the given number of turns, none of them probes.
     *
     * <p>The rules are now built from the schedule rather than a bare count,
     * because they have to state the follow-up and rephrase allowance left on
     * the question currently open as well as the question budget.</p>
     */
    /** The budget an unconfigured job runs on: the platform's. */
    private static final EffectiveInterviewTemplate TEMPLATE = new EffectiveInterviewTemplate(
            MIN, MAX, InterviewDifficulty.STANDARD, true, false);

    private CandidateInterviewSchedule after(int turns) {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setTotalQuestionsAsked(turns);
        schedule.setDistinctQuestionsAsked(turns);
        return schedule;
    }

    // ── the ceiling ───────────────────────────────────────────────────

    @Test
    void theInterviewRunsOnBelowTheCeiling() {
        assertFalse(policy.hasReachedCeiling(TEMPLATE, 0));
        assertFalse(policy.hasReachedCeiling(TEMPLATE, MAX - 1));
    }

    @Test
    void theCeilingClosesTheInterview() {
        assertTrue(policy.hasReachedCeiling(TEMPLATE, MAX), "at the ceiling the interview must close");
        assertTrue(policy.hasReachedCeiling(TEMPLATE, MAX + 5),
                "and stay closed past it — a miscount must not reopen an interview");
    }

    // ── the floor ─────────────────────────────────────────────────────

    @Test
    void theModelCannotCloseBeforeTheFloor() {
        assertFalse(policy.mayCloseEarly(TEMPLATE, 0));
        assertFalse(policy.mayCloseEarly(TEMPLATE, MIN - 1),
                "closing one question short leaves the evaluation with too little to score");
    }

    @Test
    void theModelMayCloseAtTheFloor() {
        assertTrue(policy.mayCloseEarly(TEMPLATE, MIN));
    }

    @Test
    void theFloorNeverExceedsTheCeiling() {
        // Reversed bounds would make every interview close instantly while also
        // being told it may not close.
        assertTrue(policy.getDefaultMinQuestions() <= policy.getDefaultMaxQuestions());
    }

    // ── what the model is told ────────────────────────────────────────

    @Test
    void theRulesStateTheBudgetAndWhatIsLeft() {
        String rules = policy.asSystemMessage(after(3), TEMPLATE);

        assertTrue(rules.contains("3 question(s) asked"), "the model needs its position in the interview");
        assertTrue(rules.contains(String.valueOf(MAX - 3)), "and how many it has left");
    }

    @Test
    void belowTheFloorTheModelIsToldNotToClose() {
        String rules = policy.asSystemMessage(after(2), TEMPLATE);

        assertTrue(rules.contains("Do not close the interview yet"));
        assertFalse(rules.contains("If you have seen enough"),
                "offering an early close below the floor contradicts the instruction above it");
    }

    @Test
    void atTheFloorTheModelIsOfferedTheClosingMarker() {
        String rules = policy.asSystemMessage(after(MIN), TEMPLATE);

        assertTrue(rules.contains(InterviewConductPolicy.COMPLETION_MARKER));
        assertFalse(rules.contains("Do not close the interview yet"));
    }

    @Test
    void theRulesAlwaysCarryTheOneQuestionAndCodingTagContract() {
        // Both are contracts with the rest of the system rather than style: two
        // questions in a turn corrupts the transcript, and an untagged coding
        // question asks for code with no editor to write it in.
        for (int asked : new int[] { 0, MIN, MAX }) {
            String rules = policy.asSystemMessage(after(asked), TEMPLATE);
            assertTrue(rules.contains("ONE question per reply"), "missing at " + asked);
            assertTrue(rules.contains(InterviewConductPolicy.CODING_TAG), "missing at " + asked);
        }
    }

    @Test
    @DisplayName("the coding tag is in the tag list the model is given, not only in the rules")
    void theTagListIncludesCoding() {
        // The list is prefaced "put these at the very start of your reply",
        // which a model reads as the complete set. Leaving [CODING] out of it
        // while a numbered rule elsewhere asked for it meant the tag stopped
        // being emitted: the editor never opened, and candidates were asked to
        // write code with nowhere to write it.
        String rules = policy.asSystemMessage(after(3), TEMPLATE);

        int tagsSection = rules.indexOf("Tags.");
        assertTrue(tagsSection >= 0, "the tag protocol should be in the prompt");

        String tags = rules.substring(tagsSection);
        assertTrue(tags.contains(InterviewConductPolicy.CODING_TAG),
                "[CODING] must be listed with the other tags, or the model drops it");
        assertTrue(tags.contains("[SCORE:"), "sanity: the list still describes the score tag");
    }

    @Test
    void theFirstQuestionIsAnIntroduction() {
        String rules = policy.asSystemMessage(after(0), TEMPLATE);

        assertTrue(rules.contains("introduce themselves"),
                "opening cold on a technical question gives a nervous candidate nothing to settle into");
        assertTrue(rules.contains("Do not ask anything technical yet"));
    }

    @Test
    void technicalQuestionsStartRightAfterTheIntroduction() {
        String rules = policy.asSystemMessage(after(1), TEMPLATE);

        assertTrue(rules.contains("Move on to technical questions"));
        assertFalse(rules.contains("introduce themselves"),
                "asking for an introduction twice wastes a question from the budget");
    }

    @Test
    void theOpeningInstructionsAreGoneOnceTheInterviewIsUnderWay() {
        String rules = policy.asSystemMessage(after(5), TEMPLATE);

        assertFalse(rules.contains("introduce themselves"));
        assertFalse(rules.contains("Move on to technical questions"));
    }

    // ── reading the model's reply ─────────────────────────────────────

    @Test
    void aReplyCarryingTheMarkerCloses() {
        assertTrue(policy.isClosing("That is all I need. " + InterviewConductPolicy.COMPLETION_MARKER));
    }

    @Test
    void theLegacyClosingPhraseAlsoCloses() {
        // interview-prompts.properties is still the live system prompt for any
        // job with no interview prompt saved, and it asks the model to say
        // "Interview completed." rather than emit the marker. Recognising only
        // the marker left those interviews running to the question ceiling
        // after the interviewer had already signed off.
        assertTrue(policy.isClosing("Thanks for your time. Interview completed."));
        assertTrue(policy.isClosing("interview completed"), "the phrase is prose, so case must not matter");
    }

    @Test
    void theLegacyClosingPhraseIsLeftInWhatTheCandidateHears() {
        // Unlike the bracketed marker, this is a sentence the interviewer meant
        // to say; stripping it would truncate the farewell.
        String spoken = policy.stripCompletionMarker("Thanks for your time. Interview completed.");

        assertEquals("Thanks for your time. Interview completed.", spoken);
    }

    @Test
    void anOrdinaryQuestionDoesNotClose() {
        assertFalse(policy.isClosing("Thanks. Now, what is a hash map?"));
        assertFalse(policy.isClosing(null), "a missing reply is not a completed interview");
    }

    @Test
    void theMarkerIsNotSpokenToTheCandidate() {
        String spoken = policy.stripCompletionMarker(
                "Thank you for your time, that is everything. " + InterviewConductPolicy.COMPLETION_MARKER);

        assertEquals("Thank you for your time, that is everything.", spoken);
        assertFalse(spoken.contains(InterviewConductPolicy.COMPLETION_MARKER));
    }

    @Test
    void strippingHandlesAMissingReply() {
        assertEquals("", policy.stripCompletionMarker(null));
    }
}
