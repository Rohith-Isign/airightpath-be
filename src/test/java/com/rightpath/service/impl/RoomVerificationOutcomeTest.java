package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.service.AiRoomVerificationService.RoomVerificationResult;
import com.rightpath.service.OpenAiVisionService;

/**
 * What the candidate is told after the room check runs.
 *
 * <p>This returned a bare boolean, and the phone turned every {@code false}
 * into "Verification failed. Please reposition the phone." A second person in
 * frame, a laptop out of shot, a blank photograph and an OpenAI outage all
 * read identically — and only two of those are something a candidate can do
 * anything about. Repositioning a phone does not fix an outage, so the
 * candidate looped until they gave up.</p>
 *
 * <p>The distinction these tests pin down is therefore not cosmetic: rejected
 * means "change something and retry", unavailable means "this is not your
 * fault, wait".</p>
 */
class RoomVerificationOutcomeTest {

    /** Comfortably over the blank-frame floor, so size is never what decides. */
    private static final byte[] A_REAL_PHOTO = new byte[8192];

    private OpenAiVisionService vision;
    private AiRoomVerificationServiceImpl service;

    @BeforeEach
    void setUp() {
        vision = mock(OpenAiVisionService.class);
        service = new AiRoomVerificationServiceImpl();
        ReflectionTestUtils.setField(service, "openAiVisionService", vision);
    }

    @Test
    @DisplayName("a verified room passes with nothing to report")
    void verifiedPasses() {
        when(vision.analyzeRoom(any())).thenReturn("{\"status\":\"VERIFIED\",\"reason\":\"\"}");

        RoomVerificationResult result = service.verify(A_REAL_PHOTO);

        assertThat(result.valid()).isTrue();
        assertThat(result.checkable()).isTrue();
    }

    @Test
    @DisplayName("a rejection carries the model's own explanation to the candidate")
    void rejectionKeepsTheReason() {
        when(vision.analyzeRoom(any())).thenReturn(
                "{\"status\":\"FAILED\",\"reason\":\"No laptop screen is visible in the frame.\"}");

        RoomVerificationResult result = service.verify(A_REAL_PHOTO);

        assertThat(result.valid()).isFalse();
        assertThat(result.checkable()).as("the check ran; it said no").isTrue();
        // The whole point: this is what the candidate needs to act on, and it
        // was being read off the response and thrown away.
        assertThat(result.reason()).contains("laptop screen");
    }

    @Test
    @DisplayName("a rejection with no stated reason still says something useful")
    void rejectionWithoutAReasonFallsBackToGuidance() {
        when(vision.analyzeRoom(any())).thenReturn("{\"status\":\"FAILED\",\"reason\":\"\"}");

        RoomVerificationResult result = service.verify(A_REAL_PHOTO);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isNotBlank();
        assertThat(result.reason()).contains("in frame");
    }

    @Test
    @DisplayName("JSON wrapped in a code fence is still read")
    void fencedJsonIsUnwrapped() {
        // A pass arriving fenced used to throw, and the candidate was told to
        // reposition a phone that was already correct.
        when(vision.analyzeRoom(any()))
                .thenReturn("```json\n{\"status\":\"VERIFIED\",\"reason\":\"\"}\n```");

        assertThat(service.verify(A_REAL_PHOTO).valid()).isTrue();
    }

    @Test
    @DisplayName("an outage is not reported to the candidate as a failed room")
    void outageIsUnavailableNotRejected() {
        when(vision.analyzeRoom(any())).thenThrow(new RuntimeException("OpenAI is down"));

        RoomVerificationResult result = service.verify(A_REAL_PHOTO);

        assertThat(result.valid()).isFalse();
        // The distinction that matters. Telling them to reposition sends them
        // round a loop that cannot succeed.
        assertThat(result.checkable()).isFalse();
        assertThat(result.reason()).contains("unavailable");
    }

    @Test
    @DisplayName("an unreadable reply is our problem, not the candidate's room")
    void unparseableReplyIsUnavailable() {
        when(vision.analyzeRoom(any())).thenReturn("I had trouble reading that image, sorry!");

        RoomVerificationResult result = service.verify(A_REAL_PHOTO);

        assertThat(result.valid()).isFalse();
        assertThat(result.checkable()).isFalse();
    }

    @Test
    @DisplayName("a reply missing the status field does not pass by accident")
    void missingStatusDoesNotPass() {
        when(vision.analyzeRoom(any())).thenReturn("{\"reason\":\"looks fine to me\"}");

        assertThat(service.verify(A_REAL_PHOTO).valid()).isFalse();
    }

    @Test
    @DisplayName("a blank frame is caught before it costs a vision call")
    void blankFrameIsRejectedLocally() {
        // The phone fires the shutter before its camera is ready and sends a
        // few bytes of nothing. Sent onward it comes back "no person visible",
        // which reads as a failed room rather than a failed photograph.
        RoomVerificationResult result = service.verify(new byte[16]);

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("did not capture");
        org.mockito.Mockito.verifyNoInteractions(vision);
    }

    @Test
    @DisplayName("no image at all is handled like a blank one")
    void nullImageIsRejected() {
        assertThat(service.verify(null).valid()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(vision);
    }
}
