package com.rightpath.service;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewReview;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.InterviewResult;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewReviewRepository;

/**
 * Recording what a person decided about a finished interview.
 *
 * <p>Deliberately narrow. Saving a review changes the interview's recorded
 * outcome and nothing else: it does not move the candidate through the hiring
 * pipeline, send them anything, or unlock the next round. Those are separate
 * actions a recruiter already takes explicitly, and an override that quietly
 * rejected somebody — or advanced them — would be a surprising amount of
 * consequence for a form with a notes box on it. If that coupling is wanted
 * later it belongs here, stated, rather than as a side effect discovered in
 * production.</p>
 */
@Service
public class InterviewReviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewReviewService.class);

    private final InterviewReviewRepository reviewRepository;
    private final CandidateInterviewScheduleRepository scheduleRepository;

    public InterviewReviewService(InterviewReviewRepository reviewRepository,
                                  CandidateInterviewScheduleRepository scheduleRepository) {
        this.reviewRepository = reviewRepository;
        this.scheduleRepository = scheduleRepository;
    }

    public Optional<InterviewReview> find(Long scheduleId) {
        return reviewRepository.findByInterviewScheduleId(scheduleId);
    }

    /** Reviews for a page of results, in one query rather than one per row. */
    public List<InterviewReview> findAll(List<Long> scheduleIds) {
        if (scheduleIds == null || scheduleIds.isEmpty()) {
            return List.of();
        }
        return reviewRepository.findAllByInterviewScheduleIdIn(scheduleIds);
    }

    /**
     * The result that stands: a reviewer's, where they set one, else the AI's.
     *
     * <p>One place that answers this, because the question is asked from the
     * results list, the detail screen and anything that later decides what to
     * do with the candidate. Three implementations of "unless a human
     * overrode it" is three chances for one of them to show the wrong outcome
     * next to the right one.</p>
     */
    public InterviewResult effectiveResult(CandidateInterviewSchedule schedule) {
        return find(schedule.getId())
                .map(InterviewReview::getOverriddenResult)
                .orElse(schedule.getInterviewResult());
    }

    /**
     * Creates or replaces the review on an interview.
     *
     * @param overriddenResult the result to impose, or null to let the AI's stand
     * @throws IllegalArgumentException when an override arrives without a reason
     * @throws ResourceNotFoundException when the interview does not exist
     */
    @Transactional
    public InterviewReview save(Long scheduleId, String reviewerEmail, String notes,
                                InterviewResult overriddenResult, String overrideReason) {
        CandidateInterviewSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview " + scheduleId + " was not found."));

        // Reviewing an interview still in progress would be reviewing a
        // transcript that is still being written, and the override would be
        // overwritten by the evaluation that runs when it finishes.
        if (schedule.getAttemptStatus() == AttemptStatus.IN_PROGRESS) {
            throw new IllegalArgumentException("This interview is still in progress — it cannot be reviewed yet.");
        }

        boolean overriding = overriddenResult != null;
        if (overriding && (overrideReason == null || overrideReason.isBlank())) {
            throw new IllegalArgumentException("Changing the result needs a reason.");
        }

        InterviewReview review = reviewRepository.findByInterviewScheduleId(scheduleId)
                .orElseGet(() -> InterviewReview.builder().interviewScheduleId(scheduleId).build());

        review.setReviewerEmail(reviewerEmail);
        review.setNotes(notes);
        review.setOverriddenResult(overriddenResult);
        // Cleared alongside the override. A reason left behind after the
        // override is withdrawn reads as though the result is still overturned.
        review.setOverrideReason(overriding ? overrideReason : null);

        InterviewReview saved = reviewRepository.save(review);

        if (overriding) {
            log.info("Interview {} result overridden to {} by {} — {}",
                    scheduleId, overriddenResult, reviewerEmail, overrideReason);
        } else {
            log.info("Interview {} reviewed by {} with no change to the result", scheduleId, reviewerEmail);
        }
        return saved;
    }
}
