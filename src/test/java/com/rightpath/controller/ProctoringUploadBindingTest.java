package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ProctoringCaptureDto;
import com.rightpath.enums.ProctoringCaptureType;
import com.rightpath.service.ExamProctoringService;

/**
 * The wire format itself: what the browser actually posts, bound to what the
 * controller actually receives.
 *
 * <p>The service-level tests cover the rules; these cover the part names and the
 * repeated-field handling, which no amount of mocking further down would catch.
 * A room sweep arrives as N parts sharing one name, and the count is a client
 * setting — so the binding has to accept whatever length turns up.</p>
 */
class ProctoringUploadBindingTest {

	private static final String CANDIDATE = "candidate@example.com";

	private ExamProctoringService proctoring;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		proctoring = mock(ExamProctoringService.class);
		mvc = MockMvcBuilders.standaloneSetup(new ExamProctoringController(proctoring)).build();
	}

	@Test
	void theIdentityPhotoPostBindsEveryFieldTheFrontendSends() throws Exception {
		when(proctoring.saveIdentityPhoto(anyString(), anyString(), anyString(), any(), anyString()))
				.thenReturn(capture(1L, ProctoringCaptureType.IDENTITY_PHOTO, 0));

		mvc.perform(multipart("/api/exam-proctoring/identity-photo")
				.file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
				.part(new MockPart("assessmentId", "42".getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("candidateEmail", CANDIDATE.getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("capturedAt", "2026-08-08T09:15:30.000Z".getBytes(StandardCharsets.UTF_8)))
				.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a"))
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.captureType").value("IDENTITY_PHOTO"))
				.andExpect(jsonPath("$.data.capturedAt").doesNotExist());

		ArgumentCaptor<MultipartFile> photo = ArgumentCaptor.forClass(MultipartFile.class);
		verify(proctoring).saveIdentityPhoto(eq("42"), eq(CANDIDATE), eq("2026-08-08T09:15:30.000Z"), photo.capture(),
				eq(CANDIDATE));
		assertEquals("photo.jpg", photo.getValue().getOriginalFilename());
	}

	@Test
	void theCallerIsTakenFromTheTokenNotTheForm() throws Exception {
		when(proctoring.saveIdentityPhoto(anyString(), anyString(), any(), any(), anyString()))
				.thenReturn(capture(1L, ProctoringCaptureType.IDENTITY_PHOTO, 0));

		mvc.perform(multipart("/api/exam-proctoring/identity-photo")
				.file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
				.part(new MockPart("assessmentId", "42".getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("candidateEmail", "someone.else@example.com".getBytes(StandardCharsets.UTF_8)))
				.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a")))
				.andExpect(status().isOk());

		// The form's candidateEmail is passed through for the service to compare
		// against the authenticated caller — it is never the source of identity.
		verify(proctoring).saveIdentityPhoto(eq("42"), eq("someone.else@example.com"), any(), any(), eq(CANDIDATE));
	}

	@Test
	void capturedAtIsOptionalOnTheWire() throws Exception {
		when(proctoring.saveIdentityPhoto(anyString(), anyString(), any(), any(), anyString()))
				.thenReturn(capture(1L, ProctoringCaptureType.IDENTITY_PHOTO, 0));

		mvc.perform(multipart("/api/exam-proctoring/identity-photo")
				.file(new MockMultipartFile("photo", "photo.jpg", "image/jpeg", jpegBytes()))
				.part(new MockPart("assessmentId", "42".getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("candidateEmail", CANDIDATE.getBytes(StandardCharsets.UTF_8)))
				.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a")))
				.andExpect(status().isOk());
	}

	@Test
	void aRoomScanOfAnyLengthBindsAsRepeatedFrameParts() throws Exception {
		when(proctoring.saveRoomScan(anyString(), anyString(), any(), any(), anyString()))
				.thenReturn(List.of(capture(1L, ProctoringCaptureType.ROOM_SCAN_FRAME, 0),
						capture(2L, ProctoringCaptureType.ROOM_SCAN_FRAME, 1),
						capture(3L, ProctoringCaptureType.ROOM_SCAN_FRAME, 2)));

		var request = multipart("/api/exam-proctoring/room-scan")
				.part(new MockPart("assessmentId", "42".getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("candidateEmail", CANDIDATE.getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("capturedAt", "2026-08-08T09:15:30.000Z".getBytes(StandardCharsets.UTF_8)));
		for (int i = 0; i < 3; i++) {
			request = request.file(new MockMultipartFile("frames", "frame-" + i + ".jpg", "image/jpeg", jpegBytes()));
		}

		mvc.perform(request.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a"))
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.length()").value(3));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MultipartFile>> frames = ArgumentCaptor.forClass(List.class);
		verify(proctoring).saveRoomScan(eq("42"), eq(CANDIDATE), anyString(), frames.capture(), eq(CANDIDATE));

		assertEquals(3, frames.getValue().size(), "every part sharing the frames name has to arrive");
		assertEquals("frame-0.jpg", frames.getValue().get(0).getOriginalFilename(), "sweep order is preserved");
		assertEquals("frame-2.jpg", frames.getValue().get(2).getOriginalFilename());
	}

	@Test
	void anEightFrameSweepBindsJustAsWellAsAThreeFrameOne() throws Exception {
		when(proctoring.saveRoomScan(anyString(), anyString(), any(), any(), anyString())).thenReturn(List.of());

		var request = multipart("/api/exam-proctoring/room-scan")
				.part(new MockPart("assessmentId", "42".getBytes(StandardCharsets.UTF_8)))
				.part(new MockPart("candidateEmail", CANDIDATE.getBytes(StandardCharsets.UTF_8)));
		for (int i = 0; i < 8; i++) {
			request = request.file(new MockMultipartFile("frames", "frame-" + i + ".jpg", "image/jpeg", jpegBytes()));
		}

		mvc.perform(request.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a")))
				.andExpect(status().isOk());

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MultipartFile>> frames = ArgumentCaptor.forClass(List.class);
		verify(proctoring).saveRoomScan(anyString(), anyString(), any(), frames.capture(), anyString());
		assertEquals(8, frames.getValue().size(), "the default sweep length is not special-cased anywhere");
	}

	private ProctoringCaptureDto capture(Long id, ProctoringCaptureType type, int frameIndex) {
		return new ProctoringCaptureDto(id, 42L, CANDIDATE, "FIXT-PROCTOR-2026-001", type, frameIndex, "image/jpeg",
				51_200L, null, null, "/api/exam-proctoring/captures/" + id + "/image");
	}

	private byte[] jpegBytes() {
		return "fake-jpeg-bytes".getBytes(StandardCharsets.UTF_8);
	}
}
