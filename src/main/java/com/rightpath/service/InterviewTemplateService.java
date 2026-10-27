package com.rightpath.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewTemplate;
import com.rightpath.enums.InterviewDifficulty;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.InterviewTemplateRepository;

/**
 * Resolves the settings an interview runs on, and guards what may be saved.
 *
 * <p>Resolution is per field, not per row. A recruiter who sets only the
 * difficulty for their L3 round should not thereby inherit whatever question
 * budget happened to be in the row — they should keep the platform's, and get
 * the platform's again if it changes. So each field falls back on its own.</p>
 */
@Service
public class InterviewTemplateService {

    private static final Logger log = LoggerFactory.getLogger(InterviewTemplateService.class);

    /**
     * The widest budget a job may set for itself.
     *
     * <p>Not arbitrary: an interview has a wall-clock limit
     * ({@code interview.max-duration-minutes}) that closes it whether it has
     * finished or not, so a budget far above what fits in that window does not
     * buy more questions — it just guarantees every interview ends on the
     * timeout instead of on a proper close, and is graded as though it were cut
     * short.</p>
     */
    private static final int ABSOLUTE_MAX_QUESTIONS = 60;

    private final InterviewTemplateRepository templateRepository;

    @Value("${interview.questions.min:10}")
    private int defaultMinQuestions;

    @Value("${interview.questions.max:20}")
    private int defaultMaxQuestions;

    public InterviewTemplateService(InterviewTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** The settings for the interview this schedule is for. */
    public EffectiveInterviewTemplate resolve(CandidateInterviewSchedule schedule) {
        return resolve(schedule.getJobPrefix(), schedule.getEffectiveRound());
    }

    /**
     * The settings for a job's round, with the platform defaults underneath.
     *
     * <p>Never throws and never returns null. A template read that fails is not
     * a reason to fail an interview that would otherwise have run perfectly well
     * on the defaults.</p>
     */
    public EffectiveInterviewTemplate resolve(String jobPrefix, InterviewRound round) {
        Optional<InterviewTemplate> stored = find(jobPrefix, round);

        int min = stored.map(InterviewTemplate::getMinQuestions)
                .orElse(defaultMinQuestions);
        int max = stored.map(InterviewTemplate::getMaxQuestions)
                .orElse(defaultMaxQuestions);
        InterviewDifficulty difficulty = InterviewDifficulty.orDefault(
                stored.map(InterviewTemplate::getBaselineDifficulty).orElse(null));
        boolean adaptive = stored.map(InterviewTemplate::isAdaptiveDifficulty).orElse(true);

        // A row saved before a validation rule existed, or edited straight in the
        // database, must not be able to invert the bounds — a floor above the
        // ceiling means an interview told both to keep going and to stop.
        if (max < min) {
            log.warn("Interview template for {}/{} has min {} above max {}; using the platform budget",
                    jobPrefix, round, min, max);
            min = defaultMinQuestions;
            max = defaultMaxQuestions;
        }

        return new EffectiveInterviewTemplate(min, max, difficulty, adaptive, stored.isPresent());
    }

    /** The stored row for a job's round, if it has one. */
    public Optional<InterviewTemplate> find(String jobPrefix, InterviewRound round) {
        if (jobPrefix == null || jobPrefix.isBlank()) {
            return Optional.empty();
        }
        try {
            return templateRepository.findByJobPrefixAndRound(jobPrefix, InterviewRound.orDefault(round));
        } catch (Exception e) {
            log.warn("Could not read the interview template for {}/{}", jobPrefix, round, e);
            return Optional.empty();
        }
    }

    /** Every round this job has configured. */
    public List<InterviewTemplate> findAll(String jobPrefix) {
        return templateRepository.findAllByJobPrefix(jobPrefix);
    }

    /**
     * Creates or updates a job round's template.
     *
     * <p>Bounds are checked here rather than trusted from the console. The
     * numbers decide how long every candidate on this job sits, and a mistyped
     * ceiling of 200 is not caught by anything downstream — the interview simply
     * runs until the duration timeout kills it.</p>
     *
     * @throws IllegalArgumentException when the budget could not be honoured
     */
    public InterviewTemplate save(String jobPrefix, InterviewRound round, Integer minQuestions,
                                  Integer maxQuestions, InterviewDifficulty baselineDifficulty,
                                  boolean adaptiveDifficulty) {
        if (jobPrefix == null || jobPrefix.isBlank()) {
            throw new IllegalArgumentException("A job prefix is required.");
        }
        InterviewRound effectiveRound = InterviewRound.orDefault(round);

        if (minQuestions != null && minQuestions < 1) {
            throw new IllegalArgumentException("An interview must ask at least one question.");
        }
        if (maxQuestions != null && maxQuestions > ABSOLUTE_MAX_QUESTIONS) {
            throw new IllegalArgumentException(
                    "The most questions an interview may ask is " + ABSOLUTE_MAX_QUESTIONS + ".");
        }
        // Compared against what will actually apply, not only against each
        // other: setting a ceiling of 8 while leaving the floor on the
        // platform's 10 is the same broken interview as setting both.
        int effectiveMin = minQuestions != null ? minQuestions : defaultMinQuestions;
        int effectiveMax = maxQuestions != null ? maxQuestions : defaultMaxQuestions;
        if (effectiveMax < effectiveMin) {
            throw new IllegalArgumentException(
                    "The question ceiling (" + effectiveMax + ") cannot be below the floor ("
                            + effectiveMin + ").");
        }

        InterviewTemplate template = templateRepository
                .findByJobPrefixAndRound(jobPrefix, effectiveRound)
                .orElseGet(() -> InterviewTemplate.builder()
                        .jobPrefix(jobPrefix)
                        .round(effectiveRound)
                        .build());

        template.setMinQuestions(minQuestions);
        template.setMaxQuestions(maxQuestions);
        template.setBaselineDifficulty(baselineDifficulty);
        template.setAdaptiveDifficulty(adaptiveDifficulty);

        InterviewTemplate saved = templateRepository.save(template);
        log.info("Saved interview template for {}/{}: questions {}-{}, {}, adaptive={}",
                jobPrefix, effectiveRound, effectiveMin, effectiveMax,
                InterviewDifficulty.orDefault(baselineDifficulty), adaptiveDifficulty);
        return saved;
    }
}
