package com.rightpath.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.service.MobileCaptureService;
import com.rightpath.service.MobilePairingService;

/** A photo is kept from the phone only for the two reasons that warrant one. */
class PhoneProctorCaptureTest {

	private MobilePairingService pairings;
	private MobileCaptureService captures;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		pairings = mock(MobilePairingService.class);
		captures = mock(MobileCaptureService.class);
		MobileVerificationController controller = new MobileVerificationController();
		ReflectionTestUtils.setField(controller, "pairingService", pairings);
		ReflectionTestUtils.setField(controller, "captureService", captures);
		mvc = MockMvcBuilders.standaloneSetup(controller).build();
		when(pairings.resolve("tok")).thenReturn(InterviewMobilePairing.builder().token("tok").interviewScheduleId(5L).build());
	}

	private MockMultipartFile photo() {
		return new MockMultipartFile("photo", "x.jpg", "image/jpeg", new byte[] { 1, 2, 3 });
	}

	@Test
	void aSecondPersonIsKeptAndLogged() throws Exception {
		mvc.perform(multipart("/api/mobile/proctor-capture").file(photo()).param("token", "tok")
				.param("reason", "EXTRA_PERSON")).andExpect(status().isOk());

		verify(captures).saveForPairing(eq(5L), eq("EXTRA_PERSON"), any());
		verify(pairings).recordEvent(any(), eq("mobile_extra_person"), any());
	}

	@Test
	void aMissingCandidateIsKeptAndLogged() throws Exception {
		mvc.perform(multipart("/api/mobile/proctor-capture").file(photo()).param("token", "tok")
				.param("reason", "no_candidate")).andExpect(status().isOk());

		verify(captures).saveForPairing(eq(5L), eq("NO_CANDIDATE"), any());
		verify(pairings).recordEvent(any(), eq("mobile_candidate_absent"), any());
	}

	@Test
	void anyOtherReasonIsRefusedAndNothingIsStored() throws Exception {
		try {
			mvc.perform(multipart("/api/mobile/proctor-capture").file(photo()).param("token", "tok")
					.param("reason", "PERIODIC"));
		} catch (Exception refused) {
			// Surfaces as an exception without the application's error handler; either way nothing is kept.
		}

		verify(captures, never()).saveForPairing(any(), any(), any());
	}
}
