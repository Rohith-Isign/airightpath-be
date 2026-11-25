package com.rightpath.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.RoleRequirementSet;
import com.rightpath.enums.InterviewRound;

public interface RoleRequirementSetRepository extends JpaRepository<RoleRequirementSet, Long> {

    Optional<RoleRequirementSet> findFirstByJobPrefixAndRoundAndJobHashOrderByIdAsc(String jobPrefix,
            InterviewRound round, String jobHash);
}
