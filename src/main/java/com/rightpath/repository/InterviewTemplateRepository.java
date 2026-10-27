package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.InterviewTemplate;
import com.rightpath.enums.InterviewRound;

public interface InterviewTemplateRepository extends JpaRepository<InterviewTemplate, Long> {

    Optional<InterviewTemplate> findByJobPrefixAndRound(String jobPrefix, InterviewRound round);

    List<InterviewTemplate> findAllByJobPrefix(String jobPrefix);
}
