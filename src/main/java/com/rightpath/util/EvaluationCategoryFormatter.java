package com.rightpath.util;

import java.util.List;

import org.springframework.stereotype.Component;

import com.rightpath.entity.EvaluationCategory;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.EvaluationCategoryRepository;

@Component
public class EvaluationCategoryFormatter {

    private final EvaluationCategoryRepository evaluationCategoryRepository;

    // Defaults, per round, used when neither the round nor the job has a list.
    //
    // Split because the two rounds are different interviews.
    //
    // L2 is the technical round, so it is scored on technical ability only: Technical Skills and Programming carry
    // 75% of the weight, and the interviewer divides its questions by these weights, so about three in four
    // questions are technical or coding. Communication, culture fit and delivery are not scored here; they belong
    // to the behavioural round (L3). Programming is scored from the code or logic the candidate wrote in the
    // notepad, which is read and never run.
    //
    // L3 previously inherited the old combined list, which is why a behavioural round came back with a Programming
    // score for questions it never asked. Its categories are the ones a behavioural interview can evidence.
    // Either list can be replaced per job from /api/prompts/evaluation-categories; a job with its own list keeps it.
    private static final List<DefaultCategory> L2_DEFAULTS = List.of(
            new DefaultCategory("Technical Skills", 40,
                    "Depth and correctness of knowledge in the technologies this role needs"),
            new DefaultCategory("Programming", 35,
                    "Correctness, edge cases, complexity and structure of the code or logic written in the notepad"),
            new DefaultCategory("Problem Solving", 15,
                    "Analytical thinking, debugging and how a problem is approached and broken down"),
            new DefaultCategory("Logical Reasoning", 10,
                    "Sound reasoning, and whether conclusions follow from what was said")
    );

    private static final List<DefaultCategory> L3_DEFAULTS = List.of(
            new DefaultCategory("Communication", 22,
                    "Clarity, listening, and pitching an explanation at the person hearing it"),
            new DefaultCategory("Ownership", 20,
                    "Taking responsibility for outcomes, including when things went wrong"),
            new DefaultCategory("Teamwork & Collaboration", 18,
                    "Working with others, handling disagreement, and giving credit"),
            new DefaultCategory("Adaptability", 15,
                    "Coping with change, ambiguity and feedback"),
            new DefaultCategory("Role Fit & Motivation", 15,
                    "Why this role, and whether what drives them matches what the job offers"),
            new DefaultCategory("Conflict Handling", 10,
                    "Navigating friction with colleagues, managers or customers")
    );

    private static List<DefaultCategory> defaultsFor(InterviewRound round) {
        return InterviewRound.orDefault(round) == InterviewRound.L3_BEHAVIORAL ? L3_DEFAULTS : L2_DEFAULTS;
    }

    public EvaluationCategoryFormatter(EvaluationCategoryRepository evaluationCategoryRepository) {
        this.evaluationCategoryRepository = evaluationCategoryRepository;
    }

    /**
     * The categories one round is scored against.
     *
     * <p>Three places to look, in order: the round's own list, the job's shared
     * list, then the platform's defaults for that round. Falling back per list
     * rather than per category is deliberate — weights have to total 100 to mean
     * anything, and merging a round's three categories into the job's seven
     * would produce a set that sums to neither.</p>
     */
    public List<EvaluationCategory> getCategories(String jobPrefix, InterviewRound round) {
        InterviewRound effectiveRound = InterviewRound.orDefault(round);

        List<EvaluationCategory> roundSpecific =
                evaluationCategoryRepository.findAllByJobPrefixAndRound(jobPrefix, effectiveRound);
        if (roundSpecific != null && !roundSpecific.isEmpty()) {
            return roundSpecific;
        }

        List<EvaluationCategory> shared =
                evaluationCategoryRepository.findAllByJobPrefixAndRoundIsNull(jobPrefix);
        if (shared != null && !shared.isEmpty()) {
            return shared;
        }

        return defaultsFor(effectiveRound).stream()
                .map(d -> EvaluationCategory.builder()
                        .categoryName(d.name)
                        .weight(d.weight)
                        .description(d.description)
                        .round(effectiveRound)
                        .build())
                .toList();
    }

    /**
     * Build a human-readable category section for the interview START system prompt.
     */
    public String buildStartPromptCategorySection(String jobPrefix, InterviewRound round) {
        List<EvaluationCategory> categories = getCategories(jobPrefix, round);

        StringBuilder sb = new StringBuilder();
        sb.append("## Evaluation Categories & Question Distribution\n");
        sb.append("Distribute your interview questions across these categories proportionally by weight.\n\n");

        for (EvaluationCategory cat : categories) {
            sb.append(String.format("- **%s** (Weight: %.0f%%): %s\n",
                    cat.getCategoryName(),
                    cat.getWeight(),
                    cat.getDescription() != null ? cat.getDescription() : ""));
        }

        sb.append("\nEnsure each category receives at least one question. ");
        sb.append("Higher-weighted categories should receive more questions and deeper follow-ups.");

        return sb.toString();
    }

    /**
     * Build the JSON category template for the evaluation END prompt.
     */
    public String buildEvaluationCategorySection(String jobPrefix, InterviewRound round) {
        List<EvaluationCategory> categories = getCategories(jobPrefix, round);

        // Technical round only: how far the candidate got in each area, and whether their resume showed it. A
        // behavioural round has no levels, so asking for them there would produce invented ones.
        String levelFields = round == InterviewRound.L2_TECHNICAL
                ? ", \"highestLevel\": \"<NONE | BASIC | INTERMEDIATE | ADVANCED | EXPERT>\", \"mapped\": <true | false>"
                : "";

        StringBuilder sb = new StringBuilder("\"categoryScores\": [\n");
        for (int i = 0; i < categories.size(); i++) {
            EvaluationCategory cat = categories.get(i);
            double weightDecimal = cat.getWeight() / 100.0;
            sb.append(String.format(
                    "        {\"category\": \"%s\", \"score\": <0-10>, \"weight\": %.2f, \"feedback\": \"<2-3 sentences on %s: what was right, where it fell short, how to improve>\", \"questionNumbers\": [<numbers of the questions this score is based on>]%s}",
                    cat.getCategoryName(), weightDecimal, cat.getCategoryName().toLowerCase(), levelFields
            ));
            if (i < categories.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("    ]");
        return sb.toString();
    }

    private record DefaultCategory(String name, double weight, String description) {}
}
