package com.rightpath.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rightpath.entity.InterviewReview;

public interface InterviewReviewRepository extends JpaRepository<InterviewReview, Long> {

    Optional<InterviewReview> findByInterviewScheduleId(Long interviewScheduleId);

    /** For a results list, which fetches many schedules' reviews in one query. */
    List<InterviewReview> findAllByInterviewScheduleIdIn(List<Long> interviewScheduleIds);
}
