package com.rightpath.dto.voice;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationResult {

    private double overallScore;
    private String recommendation;
    private List<CategoryScore> categoryScores;
    private SpeechAnalysis speechAnalysis;
    private String summary;
    private List<String> strengths;
    private List<String> areasForImprovement;

    /**
     * How sure the grader is of its own verdict, 0.0 to 1.0.
     *
     * <p>Asked for explicitly, because a model given a thin transcript does not
     * hedge — it produces the same confident paragraph it would for a full one.
     * Null on an evaluation graded before this was asked for, and on one the
     * model declined to answer, which is itself treated as low.</p>
     *
     * <p>Not the same thing as {@link SpeechAnalysis#confidenceScore}, which is
     * how confident the <em>candidate</em> sounded. The two being named alike is
     * a trap: one is evidence about the person, the other is a caveat on the
     * machine's reading of them.</p>
     */
    private Double confidence;

    /** Whether a person should look at this before it is acted on. */
    private boolean needsHumanReview;

    /** Why, in words a reviewer can act on. Empty when no review is needed. */
    private List<String> reviewReasons;

    /**
     * The share of the scoring areas, by weight, that the questions actually asked about, 0.0 to 1.0. The overall
     * score is built only from those; the rest are not assessed and do not count for or against the candidate.
     */
    private Double coverage;

    /**
     * The mean score over the assessed areas only, out of 10: how well the candidate did on what was actually tested.
     * The overall score is lower than this whenever some areas were never reached, because an area not demonstrated
     * adds nothing to it.
     */
    private Double assessedScore;

    /** How much the score rests on: SUFFICIENT, PARTIAL (some areas never asked) or INSUFFICIENT (too little to judge). */
    private String evidenceLevel;

    /**
     * Everything a complete interview for this role and round should have tested, worked out by the grader from the
     * job and the interview plan, each with how the candidate did on it (or NOT_ASKED). The score is measured
     * against this whole list, so an interview that only reached half the concepts cannot score as if that half
     * were the whole job.
     */
    private List<Concept> expectedConcepts;

    /**
     * One entry per skill the job requires (and any job-relevant resume skill the interview tested): its score, the
     * level reached, the category it counts towards and the questions it rests on. The skill-wise report reads this;
     * the categories stay as the weighted scoring groups. Empty on a behavioural round and on older results.
     */
    private List<SkillScore> skillScores;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkillScore {
        private String skill;
        /** The scoring category it counts towards, as configured for the job. */
        private String category;
        /** Whether the resume, projects or introduction showed it. */
        private Boolean mapped;
        private double score;
        private String highestLevel;
        private List<Integer> questionNumbers;
        private String feedback;
        /** False when no question tested it: shown as not assessed, never as a zero. */
        @Builder.Default
        private boolean assessed = true;
    }

    /** The area each interviewer question was about (Frontend, Backend, Database, Communication and so on). */
    private List<QuestionArea> questionAreas;

    /**
     * For each answered question, what the answer got right and what cost it the missing marks ("7/10: the missing 3
     * were for not mentioning rollback on checked exceptions"), so a reviewer can see why each answer scored what it did.
     */
    private List<AnswerNote> answerNotes;

    /**
     * Where to improve, as concrete next steps: for each weak or untested area, what fell short in this interview and
     * exactly what to study or practise, most important first. Built from the evidence, not general advice.
     */
    private List<ImprovementItem> improvementPlan;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImprovementItem {
        /** The skill or area it is about. */
        private String skill;
        /** What fell short in this interview, with the question it showed in where there is one. */
        private String gap;
        /** What to do about it: the topics to study and the practice that would close the gap. */
        private String action;
        /** HIGH, MEDIUM or LOW: how much it matters for this role. */
        private String priority;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerNote {
        private int questionNumber;
        private String gotRight;
        private String missed;
    }

    /**
     * The score against the full scope, 0 to 100: each expected concept counts 1 when answered well, a half when
     * partly, nothing when wrong or never asked. Worked out by the server from {@link #expectedConcepts}, not taken
     * from the model. Null on an evaluation graded before this existed.
     */
    private Double scopeScore;

    /** The share of the role's requirements, out of 100, a pass needs (the configured pass mark). */
    private Double passMark;

    /** Whether the numbers pass this candidate for the role: role fit at the pass mark and every core skill shown. */
    private Boolean meetsPassMark;

    /**
     * Why the result is what it is, in a sentence or two a recruiter can repeat: the role fit against the pass mark,
     * how many of the role's requirements were shown, and the core ones missing.
     */
    private String resultReason;

    /** Required skills the role cannot do without that the candidate did not show at all (asked or not). */
    private List<String> missingCoreSkills;

    /**
     * The verdict as a senior interviewer would give it to a hiring manager: what kind of candidate this is, how deep
     * they really are, and whether they are ready for this role, in a few plain sentences.
     */
    private String verdict;

    /** The seniority the answers actually showed (for example "Junior", "Mid-level"), next to what the job asks for. */
    private String observedSeniority;

    /** What the job needs that this interview never tested, so a later round or a reviewer knows what is still open. */
    private List<String> notCovered;

    /** Where answers were right but shallow: the depth a person in this role needs and the candidate did not show. */
    private List<String> depthGaps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Concept {
        private String concept;
        /** COMMUNICATION, FRONTEND, BACKEND, DATABASE, PROGRAMMING, DEVOPS_CLOUD, SYSTEM_DESIGN, TESTING, BEHAVIOURAL, OTHER. */
        private String area;
        /** CORRECT, PARTIAL, INCORRECT or NOT_ASKED. */
        private String status;
        private List<Integer> questionNumbers;
        /**
         * What in the job this requirement comes from: SKILL (a required skill), RESPONSIBILITY (something the role
         * does day to day) or EXPERIENCE (the depth the job's experience level calls for). Null on older results.
         */
        private String requirement;
        /** The required skill it belongs to, as the job names it, when it belongs to one. */
        private String skill;
        /** CORE (the role cannot be done without it) or SUPPORTING. Core counts double. Null on older results. */
        private String importance;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuestionArea {
        private int questionNumber;
        private String area;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryScore {
        private String category;
        private double score;
        private double weight;
        private String feedback;

        /**
         * False when no question bore on this area: it was never asked about, so it is not assessed, shows as such and
         * adds nothing to the overall score. Absent (true) on an evaluation graded before this was recorded.
         */
        @Builder.Default
        private boolean assessed = true;

        /**
         * The interviewer's questions this score rests on, by the number each carries in the transcript the grader
         * was shown ("Question 3"). The results page turns them into "asked, answered, skipped" for the area and
         * lists them. Null on an evaluation graded before this was asked for; empty when the area was never asked
         * about.
         */
        private List<Integer> questionNumbers;

        /**
         * The highest level of this area the candidate actually demonstrated: NONE, BASIC, INTERMEDIATE, ADVANCED or
         * EXPERT. L2 only. Null when the grader did not say (an L3 round, or a result graded before this was asked
         * for), which the results page shows as nothing rather than as NONE.
         */
        private String highestLevel;

        /**
         * Whether the candidate's resume, projects or introduction showed this area. True means they claimed
         * exposure; it is context for how it was assessed, not proof of skill. Null when not stated.
         */
        private Boolean mapped;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SpeechAnalysis {
        private double averageWordsPerMinute;
        private int totalFillerWords;
        private double confidenceScore;
        private String paceAssessment;
        private String articulationFeedback;
    }
}
