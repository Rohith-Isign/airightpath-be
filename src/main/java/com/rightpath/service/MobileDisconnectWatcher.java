package com.rightpath.service;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.repository.CandidateInterviewScheduleRepository;

/**
 * Notes, for the reviewer, that a paired phone has gone quiet during an interview. It never ends the interview.
 *
 * <p>The phone is the second camera, but a phone that locks, loses signal or is closed is not the candidate's
 * misconduct, and submitting their interview for it was taking the whole attempt away over something they may not
 * have caused. The interview carries on; a {@code mobile_disconnected} proctoring event is filed once per silence so
 * the reviewer can see when the phone went and judge it.</p>
 */
@Component
public class MobileDisconnectWatcher {

    private static final Logger log = LoggerFactory.getLogger(MobileDisconnectWatcher.class);

    private final MobilePairingService pairingService;
    private final CandidateInterviewScheduleRepository scheduleRepo;
    /** Interviews whose current silence has already been filed, so a long silence is one event, not one per sweep. */
    private final Set<Long> flagged = ConcurrentHashMap.newKeySet();

    @Value("${mobile.disconnect.grace-seconds:60}")
    private long graceSeconds;

    public MobileDisconnectWatcher(MobilePairingService pairingService,
            CandidateInterviewScheduleRepository scheduleRepo) {
        this.pairingService = pairingService;
        this.scheduleRepo = scheduleRepo;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void sweep() {
        try {
            Set<Long> silentNow = new HashSet<>();
            for (InterviewMobilePairing pairing : pairingService.silentFor(graceSeconds)) {
                silentNow.add(pairing.getInterviewScheduleId());
                note(pairing);
            }
            flagged.retainAll(silentNow); // a phone that came back can be flagged again if it goes again
        } catch (Exception e) {
            log.error("Could not check for phones that have gone", e);
        }
    }

    private void note(InterviewMobilePairing pairing) {
        Long scheduleId = pairing.getInterviewScheduleId();
        boolean live = scheduleRepo.findAttemptStatusById(scheduleId)
                .map(status -> status == AttemptStatus.IN_PROGRESS).orElse(false);
        if (!live || !flagged.add(scheduleId)) {
            return; // not running, or already filed for this silence
        }
        pairingService.recordEvent(pairing, "mobile_disconnected",
                "phone not heard from for " + graceSeconds + "s; interview continues");
    }
}
