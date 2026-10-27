package com.rightpath.enums;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The round model, and the compatibility rules it has to honour.
 *
 * <p>Rounds were added to a system that already ran interviews, so most of what
 * matters here is about not disturbing what exists: a schedule with no round
 * recorded is a technical interview, and a job holding only the old
 * round-agnostic prompt keeps using it.</p>
 */
class InterviewRoundTest {

    @Test
    void aMissingRoundReadsAsTechnical() {
        // Every interview booked before the column existed was a technical one,
        // because that was the only interview there was. Treating null as
        // "unknown" instead would drop historic results out of both filters.
        assertSame(InterviewRound.L2_TECHNICAL, InterviewRound.orDefault(null));
        assertSame(InterviewRound.L2_TECHNICAL, InterviewRound.DEFAULT);
    }

    @Test
    void anExplicitRoundIsNeverOverridden() {
        for (InterviewRound round : InterviewRound.values()) {
            assertSame(round, InterviewRound.orDefault(round));
        }
    }

    @Test
    void everyRoundHasItsOwnPromptType() {
        // Two rounds sharing a prompt type would silently overwrite each other:
        // JobPrompt is keyed on (jobPrefix, promptType, promptStage), so saving
        // the second round's prompt would collide with the first.
        Set<PromptType> claimed = new HashSet<>();
        for (InterviewRound round : InterviewRound.values()) {
            assertNotNull(round.getPromptType(), () -> round + " has no prompt type");
            assertNotEquals(PromptType.INTERVIEW, round.getPromptType(),
                    () -> round + " must not claim the legacy round-agnostic prompt");
            assertTrue(claimed.add(round.getPromptType()),
                    () -> round.getPromptType() + " is claimed by more than one round");
        }
    }

    @Test
    void theLegacyPromptTypeStillExists() {
        // Removing it would strand every job configured before rounds existed:
        // their single INTERVIEW prompt is the fallback both rounds resolve to.
        assertNotNull(PromptType.valueOf("INTERVIEW"));
    }

    @Test
    void everyRoundIsLabelledForScreens() {
        for (InterviewRound round : InterviewRound.values()) {
            assertNotNull(round.getDisplayName());
            assertFalse(round.getDisplayName().isBlank(), () -> round + " has a blank display name");
            // The enum name is storage. A recruiter should never be shown it.
            assertNotEquals(round.name(), round.getDisplayName());
        }
    }

    @Test
    void bothRoundsAreDistinctAndNamedByLevel() {
        // The levels are how this team refers to the rounds, so the names carry
        // them rather than only describing the content.
        assertTrue(InterviewRound.L2_TECHNICAL.name().startsWith("L2"));
        assertTrue(InterviewRound.L3_BEHAVIORAL.name().startsWith("L3"));
        assertNotEquals(InterviewRound.L2_TECHNICAL, InterviewRound.L3_BEHAVIORAL);
    }
}
