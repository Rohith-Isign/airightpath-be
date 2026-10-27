package com.rightpath.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rightpath.entity.InterviewRecordingUpload;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewRecordingUploadRepository;

/**
 * Finishes recordings whose candidate went away before finishing them.
 *
 * <p>A recording is one continuous file, sent to storage a piece at a time while
 * the interview runs and joined when it ends. The candidate's browser does the
 * joining. If that browser is closed, loses power or never comes back, the
 * pieces sit in storage as an unfinished upload that nobody can read — and the
 * recording of an interview that was in fact sat is lost.</p>
 *
 * <p>An upload that has been quiet for half an hour belongs to a browser that
 * is not coming back. It is joined here, with whatever pieces arrived, so the
 * reviewer gets the interview up to the moment it stopped rather than nothing.
 * An upload that never received a single piece has nothing to join and is
 * thrown away.</p>
 *
 * <p>If the candidate does return after this has run, their next attempt to
 * send a piece is told the upload is finished, and they stop.</p>
 */
@Component
public class RecordingUploadSweeper {

    private static final Logger log = LoggerFactory.getLogger(RecordingUploadSweeper.class);

    /** Longer than any pause in a live upload, shorter than a reviewer would wait for a recording. */
    private static final long QUIET_FOR_MINUTES = 30;

    private final InterviewRecordingUploadRepository uploads;
    private final RecordingUploadCoordinator coordinator;
    private final StorageService storageService;
    private final CandidateInterviewScheduleRepository scheduleRepo;

    /** After the interview is over, a recording this quiet has had its last piece. */
    private static final long FINISHED_QUIET_MINUTES = 2;

    @Value("${aws.s3.prefix.interview:interview}")
    private String interviewPrefix;

    public RecordingUploadSweeper(InterviewRecordingUploadRepository uploads, RecordingUploadCoordinator coordinator,
            StorageService storageService, CandidateInterviewScheduleRepository scheduleRepo) {
        this.scheduleRepo = scheduleRepo;
        this.uploads = uploads;
        this.coordinator = coordinator;
        this.storageService = storageService;
    }

    @Scheduled(fixedDelay = 60 * 1000, initialDelay = 2 * 60 * 1000)
    public void sweep() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusMinutes(QUIET_FOR_MINUTES);

        // A claim whose holder died partway through the join: hand it back.
        for (InterviewRecordingUpload stuck : uploads.findByStatusAndLastActivityAtBefore(
                InterviewRecordingUpload.COMPLETING, cutoff)) {
            uploads.release(stuck.getId(), now);
            log.warn("Released a stuck recording join for interview {} ({})", stuck.getInterviewScheduleId(),
                    stuck.getKind());
        }

        // An interview that is over and a recording that has gone quiet for a
        // couple of minutes: the last piece is in. Joined now, so the reviewer
        // has the phone's full recording right after submit, not half an hour on.
        LocalDateTime finishedCutoff = now.minusMinutes(FINISHED_QUIET_MINUTES);
        java.util.List<InterviewRecordingUpload> toJoin = new java.util.ArrayList<>(
                uploads.findByStatusAndLastActivityAtBefore(InterviewRecordingUpload.OPEN, cutoff));
        for (InterviewRecordingUpload candidate : uploads.findByStatusAndLastActivityAtBefore(
                InterviewRecordingUpload.OPEN, finishedCutoff)) {
            boolean over = scheduleRepo.findById(candidate.getInterviewScheduleId())
                    .map(sch -> sch.getAttemptStatus() == AttemptStatus.COMPLETED).orElse(false);
            if (over && toJoin.stream().noneMatch(u -> u.getId().equals(candidate.getId()))) {
                toJoin.add(candidate);
            }
        }

        for (InterviewRecordingUpload quiet : toJoin) {
            try {
                if (quiet.getPartsReceived() == 0) {
                    storageService.abortMultipartUpload(interviewPrefix, quiet.getBlobName(), quiet.getUploadId());
                    uploads.markAborted(quiet.getId());
                    log.info("Discarded an empty {} upload for interview {}", quiet.getKind(),
                            quiet.getInterviewScheduleId());
                    continue;
                }
                coordinator.complete(quiet.getInterviewScheduleId(), quiet.getKind(), quiet.getBlobName(),
                        quiet.getUploadId());
                log.info("Finished an abandoned {} recording for interview {} ({} pieces)", quiet.getKind(),
                        quiet.getInterviewScheduleId(), quiet.getPartsReceived());
            } catch (Exception e) {
                // Tried again on the next pass.
                log.warn("Could not finish the {} recording for interview {}: {}", quiet.getKind(),
                        quiet.getInterviewScheduleId(), e.getMessage());
            }
        }
    }
}
