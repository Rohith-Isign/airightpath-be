package com.rightpath.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.EvaluationCategory;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.util.EvaluationCategoryFormatter;

/**
 * Which subjects the interview was supposed to reach, and which it has.
 *
 * <p>The job's evaluation categories are the syllabus. The grader already scores
 * against every one of them, weighted, at the end — so an interview that never
 * asked about half of them produces scores for questions that were never put.
 * The prompt asked the model to spread its questions across the categories, but
 * asking is not the same as checking: a model that drifts into the subject it
 * finds most interesting still reports back that it covered everything.</p>
 *
 * <p>Coverage is read from the {@code topic} stamped on each interviewer turn,
 * so it costs nothing to compute and does not need a second model call to
 * reconstruct what was asked.</p>
 */
@Service
public class InterviewTopicCoverage {

    private final VoiceConversationEntryRepository entryRepository;
    private final EvaluationCategoryFormatter categoryFormatter;

    /**
     * Categories light enough to be skipped when the budget runs short.
     *
     * <p>A category weighted at 5% does not deserve to keep an interview open
     * when three questions remain. The heavier ones are what the score mostly
     * rests on, and those are the ones worth blocking a premature close for.</p>
     */
    @Value("${interview.topics.required-weight-threshold:10}")
    private double requiredWeightThreshold;

    public InterviewTopicCoverage(VoiceConversationEntryRepository entryRepository,
                                  EvaluationCategoryFormatter categoryFormatter) {
        this.entryRepository = entryRepository;
        this.categoryFormatter = categoryFormatter;
    }

    /** Every category configured for this job, in weight order as configured. */
    public List<EvaluationCategory> allTopics(CandidateInterviewSchedule schedule) {
        return categoryFormatter.getCategories(schedule.getJobPrefix(), schedule.getEffectiveRound());
    }

    /**
     * Topics the interview has already put a question on.
     *
     * <p>Matched case- and space-insensitively against the configured names: the
     * model writes the topic back as prose and "data structures" should not read
     * as a different subject from "Data Structures".</p>
     */
    public Set<String> covered(CandidateInterviewSchedule schedule) {
        List<VoiceConversationEntry> entries = entryRepository
                .findByInterviewScheduleIdOrderByTimestampAsc(schedule.getId());

        Set<String> configured = new LinkedHashSet<>();
        for (EvaluationCategory category : allTopics(schedule)) {
            configured.add(normalise(category.getCategoryName()));
        }

        Set<String> seen = new LinkedHashSet<>();
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() != ConversationRole.INTERVIEWER || entry.getTopic() == null) {
                continue;
            }
            String normalised = normalise(entry.getTopic());
            // Only names the job actually configured. A model inventing its own
            // heading would otherwise "cover" a category that does not exist,
            // while the one it was meant to reach stays untouched.
            if (configured.contains(normalised)) {
                seen.add(normalised);
            }
        }
        return seen;
    }

    /**
     * Configured topics still unasked and heavy enough to matter.
     *
     * <p>Returned with their real names rather than the normalised keys, because
     * this list goes back to the model as an instruction.</p>
     */
    public List<String> outstanding(CandidateInterviewSchedule schedule) {
        Set<String> covered = covered(schedule);
        return allTopics(schedule).stream()
                .filter(category -> category.getWeight() >= requiredWeightThreshold)
                .filter(category -> !covered.contains(normalise(category.getCategoryName())))
                .map(EvaluationCategory::getCategoryName)
                .toList();
    }

    /**
     * Whether the interview may close on coverage grounds.
     *
     * <p>Only ever a soft gate. It holds an interview open while there is both
     * something material left to ask and the budget to ask it — never once the
     * ceiling is in sight, because running out of questions mid-syllabus is a
     * configuration problem and keeping the candidate in the chair does not fix
     * it.</p>
     *
     * @param questionsRemaining turns left before the hard ceiling
     */
    public boolean mayCloseOnCoverage(CandidateInterviewSchedule schedule, int questionsRemaining) {
        List<String> outstanding = outstanding(schedule);
        if (outstanding.isEmpty()) {
            return true;
        }
        return questionsRemaining <= outstanding.size();
    }

    /** The instruction given when a close is held back for coverage. */
    public String buildCoverageDirective(List<String> outstanding) {
        return "Required topics not yet covered: " + String.join(", ", outstanding) + ".\n"
                + "Do not close the interview while any of these remain. Ask about one of them now, "
                + "and tag that question with its topic.";
    }

    /** The running note the model gets each turn, or null when everything is covered. */
    public String buildCoverageStatus(CandidateInterviewSchedule schedule) {
        List<String> outstanding = outstanding(schedule);
        if (outstanding.isEmpty()) {
            return null;
        }
        return "Topic coverage: still to be asked about — " + String.join(", ", outstanding)
                + ". Work these in before you close.";
    }

    private String normalise(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
