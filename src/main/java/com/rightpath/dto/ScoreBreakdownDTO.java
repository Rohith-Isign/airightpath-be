package com.rightpath.dto;

import java.util.List;

/**
 * Why a candidate's ATS score is what it is: the parts it is made of, and which of the job's key skills the resume
 * did and did not cover. Worked out again from the resume and the job as they are now, and never saved.
 *
 * @param percent       the score from the parts, as {@code AtsScorer} computes it today
 * @param storedPercent the score saved by the last screening run; differs from {@code percent} when the job was
 *                      edited, or the resume replaced, after that run. Null if never screened
 * @param threshold     the percentage at or above which a candidate is shortlisted
 * @param parts         one row per part that applied to this job
 * @param matchedSkills key skills the resume covers
 * @param missingSkills key skills the resume does not cover
 * @param note          why there is no breakdown (no readable resume), otherwise null
 */
public record ScoreBreakdownDTO(
        Double percent,
        Double storedPercent,
        double threshold,
        List<Part> parts,
        List<String> matchedSkills,
        List<String> missingSkills,
        String note) {

    /**
     * @param name   Skills, Description, Experience, Role or Education
     * @param score  how well the resume does on this part, 0 to 100
     * @param weight this part's share of the total, in percent, after parts the job gave nothing to measure were
     *               left out and the rest scaled up (so the shares always add to 100)
     * @param points what this part adds to the final score ({@code score x weight / 100})
     * @param note   what the part measures
     */
    public record Part(String name, double score, double weight, double points, String note) {
    }
}
