package com.rightpath.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.rightpath.entity.InterviewRecordingUpload;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewRecordingUploadRepository;

/**
 * Keeps track of recordings that are being sent to storage a piece at a time,
 * and makes sure each is finished exactly once.
 *
 * <p>A recording is one continuous file. It is begun when the interview starts
 * recording, grows by a piece every few minutes, and is joined when the
 * interview ends. Two things can ask for the join: the candidate's browser at
 * the end, and the server's own sweeper if the browser never came back. This is
 * where they meet — whichever arrives second is handed the first's answer
 * rather than adding the recording to the interview a second time.</p>
 */
@Service
public class RecordingUploadCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RecordingUploadCoordinator.class);

    private final InterviewRecordingUploadRepository uploads;
    private final CandidateInterviewScheduleRepository scheduleRepo;
    private final StorageService storageService;

    @Value("${aws.s3.prefix.interview:interview}")
    private String interviewPrefix;

    public RecordingUploadCoordinator(InterviewRecordingUploadRepository uploads,
            CandidateInterviewScheduleRepository scheduleRepo, StorageService storageService) {
        this.uploads = uploads;
        this.scheduleRepo = scheduleRepo;
        this.storageService = storageService;
    }

    /** A new upload has begun. */
    public void opened(Long scheduleId, String kind, String blobName, String uploadId) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        uploads.save(InterviewRecordingUpload.builder()
                .interviewScheduleId(scheduleId)
                .kind(kind)
                .blobName(blobName)
                .uploadId(uploadId)
                .status(InterviewRecordingUpload.OPEN)
                .startedAt(now)
                .lastActivityAt(now)
                .build());
    }

    /**
     * Refuse a piece for an upload that has already been finished — by the
     * server, after the candidate went quiet. 410, so the browser knows to stop
     * rather than retry for ever.
     */
    public void requireOpen(String uploadId) {
        InterviewRecordingUpload tracked = uploads.findFirstByUploadId(uploadId).orElse(null);
        if (tracked != null && !InterviewRecordingUpload.OPEN.equals(tracked.getStatus())) {
            throw new ResponseStatusException(HttpStatus.GONE, "This recording has already been finished");
        }
    }

    /** A piece arrived: the upload is alive. */
    public void touched(String uploadId) {
        uploads.touch(uploadId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * Join the pieces and add the recording to the interview — once.
     *
     * @return the stored reference; the same one on every call after the first
     */
    public String complete(Long scheduleId, String kind, String blobName, String uploadId) {
        InterviewRecordingUpload tracked = uploads.findFirstByUploadId(uploadId).orElse(null);
        if (tracked != null) {
            if (InterviewRecordingUpload.COMPLETED.equals(tracked.getStatus())) {
                return tracked.getStoredReference();
            }
            if (uploads.claim(tracked.getId(), LocalDateTime.now(ZoneOffset.UTC)) == 0) {
                InterviewRecordingUpload current = uploads.findById(tracked.getId()).orElse(tracked);
                if (InterviewRecordingUpload.COMPLETED.equals(current.getStatus())) {
                    return current.getStoredReference();
                }
                // Being joined by someone else right now. A 5xx, so the caller tries again.
                throw new IllegalStateException("This recording is being finished by another request");
            }
        }

        String url;
        try {
            url = storageService.completeMultipartUpload(interviewPrefix, blobName, uploadId);
        } catch (RuntimeException e) {
            if (tracked != null) {
                uploads.release(tracked.getId(), LocalDateTime.now(ZoneOffset.UTC));
            }
            throw e;
        }

        String separator = String.valueOf((char) 10);
        switch (kind) {
        case "screen" -> scheduleRepo.appendScreenRecordReference(scheduleId, url, separator + url);
        case "mobile" -> scheduleRepo.appendMobileRecordReference(scheduleId, url, separator + url);
        default -> scheduleRepo.appendRecordReference(scheduleId, url, separator + url);
        }
        if (tracked != null) {
            uploads.markCompleted(tracked.getId(), url);
        }
        log.info("{} recording for schedule {} stored at {} (piecewise)", kind, scheduleId, url);
        return url;
    }
}
