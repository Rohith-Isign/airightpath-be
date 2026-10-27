package com.rightpath.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.QuestionBankItem;
import com.rightpath.enums.AssessmentType;

public interface QuestionBankItemRepository extends JpaRepository<QuestionBankItem, Long> {

	Optional<QuestionBankItem> findByJobPrefixAndAssessmentTypeAndContentHash(String jobPrefix,
			AssessmentType assessmentType, String contentHash);
}
