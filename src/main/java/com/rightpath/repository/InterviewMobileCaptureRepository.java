package com.rightpath.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.InterviewMobileCapture;

public interface InterviewMobileCaptureRepository extends JpaRepository<InterviewMobileCapture, Long> {

	List<InterviewMobileCapture> findByInterviewScheduleIdOrderByCapturedAtAscIdAsc(Long interviewScheduleId);

	long countByInterviewScheduleId(Long interviewScheduleId);
}
