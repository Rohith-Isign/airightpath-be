package com.rightpath.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.CandidateProfileResume;

public interface CandidateProfileResumeRepository extends JpaRepository<CandidateProfileResume, Long> {

    Optional<CandidateProfileResume> findByEmailIgnoreCase(String email);
}
