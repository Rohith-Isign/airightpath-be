package com.rightpath.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.EvaluationCategory;
import com.rightpath.enums.InterviewRound;

public interface EvaluationCategoryRepository extends JpaRepository<EvaluationCategory, Long> {

    List<EvaluationCategory> findAllByJobPrefix(String jobPrefix);

    /** A round's own list. Empty where the job has not split its rounds. */
    List<EvaluationCategory> findAllByJobPrefixAndRound(String jobPrefix, InterviewRound round);

    /**
     * The list a job uses for every round.
     *
     * <p>A separate method rather than passing null to the one above: Spring
     * Data renders a null argument as {@code = null}, which matches nothing in
     * SQL. The fallback would come back empty and every job configured before
     * rounds existed would silently drop to the platform defaults.</p>
     */
    List<EvaluationCategory> findAllByJobPrefixAndRoundIsNull(String jobPrefix);

    void deleteAllByJobPrefix(String jobPrefix);

    void deleteAllByJobPrefixAndRound(String jobPrefix, InterviewRound round);

    void deleteAllByJobPrefixAndRoundIsNull(String jobPrefix);
}
