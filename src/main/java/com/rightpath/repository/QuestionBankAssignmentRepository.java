package com.rightpath.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.rightpath.entity.QuestionBankAssignment;

public interface QuestionBankAssignmentRepository extends JpaRepository<QuestionBankAssignment, Long> {

	boolean existsByItemIdAndAssessmentId(Long itemId, Long assessmentId);

	List<QuestionBankAssignment> findByItemIdOrderByAssignedAtDesc(Long itemId);

	/** Assessments already attributed, so a repeat sync reads only what is new. */
	@Query("SELECT DISTINCT a.assessmentId FROM QuestionBankAssignment a")
	Set<Long> findTrackedAssessmentIds();

	/** How often each question was given, and to how many different people. */
	@Query("SELECT a.itemId AS itemId, COUNT(a) AS timesAssigned, COUNT(DISTINCT a.candidateEmail) AS uniqueCandidates, "
			+ "MAX(a.assignedAt) AS lastAssignedAt FROM QuestionBankAssignment a GROUP BY a.itemId")
	List<UsageRow> usageByItem();

	interface UsageRow {
		Long getItemId();

		Long getTimesAssigned();

		Long getUniqueCandidates();

		LocalDateTime getLastAssignedAt();
	}
}
