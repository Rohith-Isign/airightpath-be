package com.rightpath.service;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.enums.CompletionReason;
import com.rightpath.repository.CandidateInterviewScheduleRepository;

import jakarta.annotation.PreDestroy;

/**
 * Ends an interview whose paired phone has gone and not come back.
 *
 * <p>The phone is the second camera: an interview carried on without it is one
 * the reviewer cannot fully see. A dropped connection is often just a network
 * blip, so the phone gets a grace period to reconnect (it re-registers with the
 * same token). Past that, the interview is submitted — ended with
 * {@code PROCTORING_VIOLATION} — and whatever the phone had already uploaded is
 * joined by {@link RecordingUploadSweeper}, so the recording up to that moment
 * is kept.</p>
 */
@Component
public class MobileDisconnectWatcher {

    private static final Logger log = LoggerFactory.getLogger(MobileDisconnectWatcher.class);

    private final MobileConnectionService connections;
    private final MobilePairingService pairingService;
    private final CandidateInterviewScheduleRepository scheduleRepo;
    private final VoiceInterviewService voiceInterviewService;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mobile-disconnect-watcher");
        t.setDaemon(true);
        return t;
    });

    @Value("${mobile.disconnect.auto-submit:true}")
    private boolean autoSubmit;

    @Value("${mobile.disconnect.grace-seconds:120}")
    private long graceSeconds;

    public MobileDisconnectWatcher(MobileConnectionService connections, MobilePairingService pairingService,
            CandidateInterviewScheduleRepository scheduleRepo, VoiceInterviewService voiceInterviewService) {
        this.connections = connections;
        this.pairingService = pairingService;
        this.scheduleRepo = scheduleRepo;
        this.voiceInterviewService = voiceInterviewService;
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        String token = connections.releaseMobileSession(event.getSessionId());
        if (token == null || !autoSubmit) {
            return;
        }
        log.info("Phone disconnected from pairing; waiting {}s for it to return", graceSeconds);
        timer.schedule(() -> check(token), graceSeconds, TimeUnit.SECONDS);
    }

    private void check(String token) {
        try {
            if (connections.getMobileSession(token) != null) {
                return; // came back
            }
            InterviewMobilePairing pairing = pairingService.resolveOrNull(token);
            if (pairing == null) {
                return;
            }
            Long scheduleId = pairing.getInterviewScheduleId();
            boolean live = scheduleRepo.findById(scheduleId)
                    .map(s -> s.getAttemptStatus() == AttemptStatus.IN_PROGRESS).orElse(false);
            if (!live) {
                return; // already over, e.g. the normal end that tells the phone to close
            }
            pairingService.recordEvent(pairing, "mobile_disconnected",
                    "phone did not reconnect within " + graceSeconds + "s; interview auto-submitted");
            voiceInterviewService.endVoiceInterview(scheduleId, CompletionReason.PROCTORING_VIOLATION);
            log.warn("Interview {} auto-submitted: phone gone for {}s", scheduleId, graceSeconds);
        } catch (Exception e) {
            log.error("Could not act on a phone disconnect", e);
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
