package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.entity.InterviewTemplate;
import com.rightpath.enums.InterviewDifficulty;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.InterviewTemplateRepository;

/**
 * What an interview actually runs on once a job's template is laid over the
 * platform defaults.
 *
 * <p>The fallback is per field, not per row, and that is the part worth pinning
 * down: a recruiter who sets only the difficulty must keep the platform's
 * question budget — and keep tracking it if it changes — rather than freezing
 * whatever happened to be in the row when they saved.</p>
 */
class InterviewTemplateResolutionTest {

    private static final String JOB = "RP-AIML-01";
    private static final int DEFAULT_MIN = 10;
    private static final int DEFAULT_MAX = 20;

    private InterviewTemplateRepository repository;
    private InterviewTemplateService service;

    @BeforeEach
    void setUp() {
        repository = mock(InterviewTemplateRepository.class);
        service = new InterviewTemplateService(repository);
        ReflectionTestUtils.setField(service, "defaultMinQuestions", DEFAULT_MIN);
        ReflectionTestUtils.setField(service, "defaultMaxQuestions", DEFAULT_MAX);
    }

    private void storedTemplate(InterviewTemplate template) {
        when(repository.findByJobPrefixAndRound(eq(JOB), any()))
                .thenReturn(Optional.ofNullable(template));
    }

    @Test
    @DisplayName("a job with no template runs entirely on the platform defaults")
    void unconfiguredJobFallsBackCompletely() {
        storedTemplate(null);

        EffectiveInterviewTemplate resolved = service.resolve(JOB, InterviewRound.L2_TECHNICAL);

        assertThat(resolved.minQuestions()).isEqualTo(DEFAULT_MIN);
        assertThat(resolved.maxQuestions()).isEqualTo(DEFAULT_MAX);
        assertThat(resolved.baselineDifficulty()).isEqualTo(InterviewDifficulty.DEFAULT);
        assertThat(resolved.adaptiveDifficulty()).isTrue();
        assertThat(resolved.configured()).isFalse();
    }

    @Test
    @DisplayName("setting only the difficulty leaves the question budget on the platform's")
    void fallbackIsPerFieldNotPerRow() {
        storedTemplate(InterviewTemplate.builder()
                .jobPrefix(JOB)
                .round(InterviewRound.L3_BEHAVIORAL)
                .baselineDifficulty(InterviewDifficulty.ADVANCED)
                .adaptiveDifficulty(true)
                .build());

        EffectiveInterviewTemplate resolved = service.resolve(JOB, InterviewRound.L3_BEHAVIORAL);

        assertThat(resolved.baselineDifficulty()).isEqualTo(InterviewDifficulty.ADVANCED);
        assertThat(resolved.minQuestions()).isEqualTo(DEFAULT_MIN);
        assertThat(resolved.maxQuestions()).isEqualTo(DEFAULT_MAX);
        assertThat(resolved.configured()).isTrue();
    }

    @Test
    @DisplayName("a configured budget is used in place of the platform's")
    void configuredBudgetWins() {
        storedTemplate(InterviewTemplate.builder()
                .jobPrefix(JOB)
                .round(InterviewRound.L2_TECHNICAL)
                .minQuestions(4)
                .maxQuestions(8)
                .build());

        EffectiveInterviewTemplate resolved = service.resolve(JOB, InterviewRound.L2_TECHNICAL);

        assertThat(resolved.minQuestions()).isEqualTo(4);
        assertThat(resolved.maxQuestions()).isEqualTo(8);
    }

    @Test
    @DisplayName("inverted bounds in a stored row fall back rather than being honoured")
    void invertedBoundsAreRejectedAtRead() {
        // Reachable by editing the database directly, or by a row written
        // before the save-time check existed. A floor above the ceiling is an
        // interview told both to keep going and to stop, which never ends well
        // for whoever is sitting it.
        storedTemplate(InterviewTemplate.builder()
                .jobPrefix(JOB)
                .round(InterviewRound.L2_TECHNICAL)
                .minQuestions(30)
                .maxQuestions(5)
                .build());

        EffectiveInterviewTemplate resolved = service.resolve(JOB, InterviewRound.L2_TECHNICAL);

        assertThat(resolved.minQuestions()).isEqualTo(DEFAULT_MIN);
        assertThat(resolved.maxQuestions()).isEqualTo(DEFAULT_MAX);
    }

    @Test
    @DisplayName("a repository failure leaves the interview on the defaults, not broken")
    void readFailureDoesNotBreakTheInterview() {
        when(repository.findByJobPrefixAndRound(eq(JOB), any()))
                .thenThrow(new RuntimeException("database is away"));

        EffectiveInterviewTemplate resolved = service.resolve(JOB, InterviewRound.L2_TECHNICAL);

        assertThat(resolved.minQuestions()).isEqualTo(DEFAULT_MIN);
        assertThat(resolved.configured()).isFalse();
    }

    // ── what may be saved ─────────────────────────────────────────────

    @Test
    @DisplayName("a ceiling below the effective floor is refused")
    void ceilingBelowFloorIsRefused() {
        storedTemplate(null);

        // Only the ceiling is set, so it is checked against the platform floor
        // of 10 rather than against nothing. Setting 8 here would otherwise save
        // cleanly and produce an interview that can never satisfy its floor.
        assertThatThrownBy(() -> service.save(JOB, InterviewRound.L2_TECHNICAL, null, 8, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be below the floor");
    }

    @Test
    @DisplayName("an absurd ceiling is refused rather than left to the duration timeout")
    void absurdCeilingIsRefused() {
        storedTemplate(null);

        assertThatThrownBy(() -> service.save(JOB, InterviewRound.L2_TECHNICAL, 5, 500, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an interview must ask at least one question")
    void zeroFloorIsRefused() {
        storedTemplate(null);

        assertThatThrownBy(() -> service.save(JOB, InterviewRound.L2_TECHNICAL, 0, 10, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a job prefix is required")
    void blankPrefixIsRefused() {
        assertThatThrownBy(() -> service.save("  ", InterviewRound.L2_TECHNICAL, 5, 10, null, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
