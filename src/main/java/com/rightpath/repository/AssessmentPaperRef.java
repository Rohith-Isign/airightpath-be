package com.rightpath.repository;

import java.time.LocalDateTime;

import com.rightpath.enums.AssessmentType;

/** Which candidate was given which paper, and where the paper is kept. */
public interface AssessmentPaperRef {

	Long getId();

	String getCandidateEmail();

	String getJobPrefix();

	AssessmentType getAssessmentType();

	LocalDateTime getAssignedAt();

	String getUploadedBy();

	String getContainerName();

	String getFileName();
}
