package com.rightpath.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.entity.ProctoringEvent;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewMobilePairingRepository;
import com.rightpath.repository.ProctoringEventRepository;

/**
 * Ties a phone's pairing token to the interview it is sitting beside, so the
 * phone can send its recording and photos without a login of its own.
 *
 * <p>The candidate's browser registers the token (signed in, and checked
 * against the interview's owner); the phone then quotes it. The token is a
 * random UUID the candidate's browser generated and showed only as a QR code,
 * so it is as hard to guess as any session id. It is also bounded: it stops
 * working after twelve hours, and it can carry a fixed amount of recording, so
 * a leaked one cannot be used to fill the bucket.</p>
 */
@Service
public class MobilePairingService {

    private static final Logger log = LoggerFactory.getLogger(MobilePairingService.class);

    private static final Pattern TOKEN_SHAPE = Pattern.compile("^[A-Za-z0-9-]{16,64}$");

    /** Longer than any interview, with room for one that was started late or resumed. */
    private static final Duration VALID_FOR = Duration.ofHours(12);

    /** An hour of phone video is a few hundred megabytes; this is generous and still finite. */
    private static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;

    private final InterviewMobilePairingRepository pairingRepository;
    private final CandidateInterviewScheduleRepository scheduleRepository;
    private final ProctoringEventRepository proctoringEventRepository;

    public MobilePairingService(InterviewMobilePairingRepository pairingRepository,
            CandidateInterviewScheduleRepository scheduleRepository,
            ProctoringEventRepository proctoringEventRepository) {
        this.pairingRepository = pairingRepository;
        this.scheduleRepository = scheduleRepository;
        this.proctoringEventRepository = proctoringEventRepository;
    }

    /** Bind a token to an interview. The candidate must own the interview. Safe to repeat. */
    public void register(Long scheduleId, String actorEmail, String token) {
        if (token == null || !TOKEN_SHAPE.matcher(token).matches()) {
            throw new IllegalArgumentException("token is not a valid pairing token");
        }
        CandidateInterviewSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + scheduleId));
        if (actorEmail == null || actorEmail.isBlank() || schedule.getEmail() == null
                || !schedule.getEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Interview " + scheduleId + " is not assigned to the authenticated candidate");
        }

        InterviewMobilePairing existing = pairingRepository.findById(token).orElse(null);
        if (existing != null) {
            if (!existing.getInterviewScheduleId().equals(scheduleId)) {
                // A token already tied to a different interview is never moved.
                throw new AccessDeniedException("This pairing token belongs to another interview");
            }
            if (Boolean.TRUE.equals(existing.getRevoked())) {
                throw new AccessDeniedException("Phone pairing is closed for this interview");
            }
            return;
        }
        // A new pairing can only be opened before the interview starts. After that the
        // proctoring set-up is what it began as; a page reloaded mid-interview makes a
        // fresh token, and that must not be a way to bring a phone in late.
        if (schedule.getAttemptStatus() != com.rightpath.enums.AttemptStatus.NOT_ATTEMPTED) {
            throw new AccessDeniedException("Phone pairing is closed once the interview has started");
        }
        pairingRepository.save(InterviewMobilePairing.builder()
                .token(token)
                .interviewScheduleId(scheduleId)
                .candidateEmail(schedule.getEmail())
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());
        log.info("Phone pairing registered for interview {}", scheduleId);
    }

    /**
     * Close phone pairing for this interview: the candidate has chosen to go ahead without one.
     *
     * <p>Every token already registered for the interview is revoked, and the one named is
     * recorded as revoked even if it was never registered, so a phone that scans that QR
     * code afterwards is told pairing is closed rather than "not found".</p>
     */
    public void revoke(Long scheduleId, String actorEmail, String token) {
        CandidateInterviewSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + scheduleId));
        if (actorEmail == null || actorEmail.isBlank() || schedule.getEmail() == null
                || !schedule.getEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Interview " + scheduleId + " is not assigned to the authenticated candidate");
        }
        for (InterviewMobilePairing pairing : pairingRepository.findByInterviewScheduleId(scheduleId)) {
            pairing.setRevoked(true);
            pairingRepository.save(pairing);
        }
        if (token != null && TOKEN_SHAPE.matcher(token).matches() && !pairingRepository.existsById(token)) {
            pairingRepository.save(InterviewMobilePairing.builder()
                    .token(token)
                    .interviewScheduleId(scheduleId)
                    .candidateEmail(schedule.getEmail())
                    .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                    .revoked(true)
                    .build());
        }
        log.info("Phone pairing closed for interview {}: started without a phone", scheduleId);
    }

    /** The phone's room scan is saved: record it where the candidate's page can read it. */
    public void markVerified(InterviewMobilePairing pairing) {
        if (pairing.getVerifiedAt() == null) {
            pairing.setVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            pairingRepository.save(pairing);
        }
    }

    /**
     * Whether this candidate's phone has completed its room scan. The candidate must own the interview and
     * the token must belong to it; anything else reads as not verified rather than leaking that a token exists.
     */
    public boolean isVerified(Long scheduleId, String actorEmail, String token) {
        InterviewMobilePairing pairing = token == null ? null : pairingRepository.findById(token).orElse(null);
        if (pairing == null || !pairing.getInterviewScheduleId().equals(scheduleId)
                || actorEmail == null || !actorEmail.equalsIgnoreCase(pairing.getCandidateEmail())
                || Boolean.TRUE.equals(pairing.getRevoked())) {
            return false;
        }
        return pairing.getVerifiedAt() != null;
    }

    /** open, closed or unknown: what a phone is told about its token before it opens the camera. */
    public String state(String token) {
        InterviewMobilePairing pairing = token == null ? null : pairingRepository.findById(token).orElse(null);
        if (pairing == null) {
            return "unknown";
        }
        if (Boolean.TRUE.equals(pairing.getRevoked())) {
            return "closed";
        }
        // The interview is over. Told to the phone here as well as over the socket, because the socket
        // message is the one that goes astray, and a phone never told keeps its recording open: nothing is
        // closed off, so the reviewer finds no phone video.
        boolean over = scheduleRepository.findAttemptStatusById(pairing.getInterviewScheduleId())
                .map(status -> status == com.rightpath.enums.AttemptStatus.COMPLETED)
                .orElse(false);
        return over ? "ended" : "open";
    }

    /** The pairing a token stands for, or a refusal. Unknown and expired look the same to the caller. */
    public InterviewMobilePairing resolve(String token) {
        InterviewMobilePairing pairing = token == null ? null : pairingRepository.findById(token).orElse(null);
        if (pairing != null && Boolean.TRUE.equals(pairing.getRevoked())) {
            throw new AccessDeniedException("Phone pairing is closed for this interview");
        }
        if (pairing == null
                || pairing.getCreatedAt().plus(VALID_FOR).isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new AccessDeniedException("This phone is not paired with an interview");
        }
        return pairing;
    }

    /** Like {@link #resolve}, but for callers that can carry on without a pairing. */
    public InterviewMobilePairing resolveOrNull(String token) {
        try {
            return resolve(token);
        } catch (AccessDeniedException e) {
            return null;
        }
    }

    /** Count recording bytes against the token, refusing once it has had its share. */
    public void accept(InterviewMobilePairing pairing, long bytes) {
        if (pairing.getBytesReceived() + bytes > MAX_BYTES) {
            throw new AccessDeniedException("This pairing has reached its upload limit");
        }
        pairingRepository.addBytes(pairing.getToken(), bytes);
    }

    /** Put what the phone reports on the interview's record, where the reviewer reads it. */
    public void recordEvent(InterviewMobilePairing pairing, String eventType, String details) {
        try {
            proctoringEventRepository.save(ProctoringEvent.builder()
                    .schedule(scheduleRepository.getReferenceById(pairing.getInterviewScheduleId()))
                    .eventType(eventType)
                    .details(details)
                    .build());
        } catch (Exception e) {
            // Reported, not thrown: the phone is mid-interview and a failure to
            // file an audit line must not cost it anything else.
            log.error("Could not record phone event {} for interview {}", eventType, pairing.getInterviewScheduleId(), e);
        }
    }
}
