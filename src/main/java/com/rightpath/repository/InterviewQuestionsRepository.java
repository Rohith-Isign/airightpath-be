package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.UploadInterviewQuestions;

public interface InterviewQuestionsRepository extends JpaRepository<UploadInterviewQuestions, Long> {

    // Get all files for a jobPrefix
    List<UploadInterviewQuestions> findByJobPrefix(String jobPrefix);

    // Get latest file (based on ID descending)
    List<UploadInterviewQuestions> findByJobPrefixOrderByIdDesc(String jobPrefix);
    
    Optional<UploadInterviewQuestions> findTopByJobPrefixAndLanguageOrderByIdDesc(String jobPrefix, String language);
}