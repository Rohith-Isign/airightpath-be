package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.enums.AttemptStatus;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewMobilePairingRepository;
import com.rightpath.repository.ProctoringEventRepository;

/**
 * A phone can only be paired before the interview starts, and not at all once the
 * candidate has chosen to go ahead without one.
 *
 * <p>Both were open: the QR code kept working after the interview began, so a
 * phone scanned a minute in turned a single-camera interview into a dual-camera
 * one half way through.</p>
 */
class MobilePairingServiceTest {

    private static final String TOKEN = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_TOKEN = "99999999-8888-7777-6666-555555555555";

    private InterviewMobilePairingRepository pairings;
    private CandidateInterviewScheduleRepository schedules;
    private MobilePairingService service;

    @BeforeEach
    void setUp() {
        pairings = mock(InterviewMobilePairingRepository.class);
        schedules = mock(CandidateInterviewScheduleRepository.class);
        service = new MobilePairingService(pairings, schedules, mock(ProctoringEventRepository.class));
    }

    private void schedule(AttemptStatus status) {
        CandidateInterviewSchedule schedule = new CandidateInterviewSchedule();
        schedule.setId(7L);
        schedule.setEmail("candidate@example.com");
        schedule.setAttemptStatus(status);
        when(schedules.findById(7L)).thenReturn(Optional.of(schedule));
    }

    private InterviewMobilePairing pairing(String token, Boolean revoked) {
        return InterviewMobilePairing.builder()
                .token(token)
                .interviewScheduleId(7L)
                .candidateEmail("candidate@example.com")
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .revoked(revoked)
                .build();
    }

    @Test
    void aPairingCanBeOpenedBeforeTheInterviewStarts() {
        schedule(AttemptStatus.NOT_ATTEMPTED);
        service.register(7L, "candidate@example.com", TOKEN);
        verify(pairings).save(any(InterviewMobilePairing.class));
    }

    @Test
    void aNewPairingIsRefusedOnceTheInterviewHasStarted() {
        schedule(AttemptStatus.IN_PROGRESS);
        assertThrows(AccessDeniedException.class, () -> service.register(7L, "candidate@example.com", TOKEN));
        verify(pairings, never()).save(any(InterviewMobilePairing.class));
    }

    @Test
    void aRevokedTokenCannotBeRegisteredAgain() {
        schedule(AttemptStatus.NOT_ATTEMPTED);
        when(pairings.findById(TOKEN)).thenReturn(Optional.of(pairing(TOKEN, true)));
        assertThrows(AccessDeniedException.class, () -> service.register(7L, "candidate@example.com", TOKEN));
    }

    @Test
    void startingWithoutAPhoneRevokesEveryTokenForTheInterview() {
        schedule(AttemptStatus.NOT_ATTEMPTED);
        InterviewMobilePairing registered = pairing(OTHER_TOKEN, null);
        when(pairings.findByInterviewScheduleId(7L)).thenReturn(List.of(registered));
        when(pairings.existsById(TOKEN)).thenReturn(false);

        service.revoke(7L, "candidate@example.com", TOKEN);

        assertEquals(Boolean.TRUE, registered.getRevoked());
        // The QR code on screen was never registered, but it is closed all the same.
        verify(pairings).save(org.mockito.ArgumentMatchers.argThat(
                row -> TOKEN.equals(row.getToken()) && Boolean.TRUE.equals(row.getRevoked())));
    }

    @Test
    void onlyTheCandidateWhoOwnsTheInterviewCanClosePairing() {
        schedule(AttemptStatus.NOT_ATTEMPTED);
        assertThrows(AccessDeniedException.class, () -> service.revoke(7L, "someone-else@example.com", TOKEN));
    }

    @Test
    void aRevokedTokenIsRefusedForUploadsAndReportedAsClosed() {
        when(pairings.findById(TOKEN)).thenReturn(Optional.of(pairing(TOKEN, true)));
        assertThrows(AccessDeniedException.class, () -> service.resolve(TOKEN));
        assertEquals("closed", service.state(TOKEN));
    }

    @Test
    void anOpenAndAnUnknownTokenAreToldApart() {
        when(pairings.findById(TOKEN)).thenReturn(Optional.of(pairing(TOKEN, null)));
        assertEquals("open", service.state(TOKEN));
        assertEquals("unknown", service.state(OTHER_TOKEN));
    }
}
