package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ProctoringCaptureDto;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.ExamProctoringCapture;
import com.rightpath.enums.AssessmentType;
import com.rightpath.enums.ProctoringCaptureType;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.ExamProctoringCaptureRepository;
import com.rightpath.service.StorageService;

/**
 * Pre-exam captures: who is allowed to file one, and what gets kept.
 *
 * <p>The candidate is never blocked by these uploads — the exam starts either
 * way — so the guarantees that matter are on the storage side: a capture is only
 * ever recorded against an attempt the caller actually owns, one identity photo
 * survives per attempt, and a room sweep is stored at whatever length the client
 * was configured to send.</p>
 */
class ExamProctoringServiceTest {

	private static final String CANDIDATE = "candidate@example.com";
	private static final String JOB_PREFIX = "FIXT-PROCTOR-2026-001";
	private static final Long ASSESSMENT_ID = 42L;

	private ExamProctoringCaptureRepository captures;
	private AssessmentRepository assessments;
	private CandidateInterviewScheduleRepository schedules;
	private StorageService storage;
	private ExamProctoringServiceImpl service;

	@BeforeEach
	void setUp() {
		captures = mock(ExamProctoringCaptureRepository.class);
		assessments = mock(AssessmentRepository.class);
		// Captures can now belong to an interview as well as an assessment; these
		// cases only exercise the assessment path, so the schedule lookup is a
		// stub that is never reached.
		schedules = mock(CandidateInterviewScheduleRepository.class);
		storage = mock(StorageService.class);
		service = new ExamProctoringServiceImpl(captures, assessments, schedules, storage);

		ReflectionTestUtils.setField(service, "proctoringPrefix", "proctoring");
		ReflectionTestUtils.setField(service, "maxFileSizeBytes", 5_242_880L);
		ReflectionTestUtils.setField(service, "maxRoomScanFrames", 32);

		when(assessments.findById(ASSESSMENT_ID)).thenReturn(Optional.of(assessment()));
		when(captures.save(any(ExamProctoringCapture.class))).thenAnswer(invocation -> {
			ExamProctoringCapture saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(1L);
			}
			return saved;
		});
	}

	@Test
	void identityPhotoIsStoredAgainstTheAttempt() {
		ProctoringCaptureDto stored = service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID), CANDIDATE,
				"2026-08-08T09:15:30.000Z", jpeg("photo"), CANDIDATE);

		assertEquals(ASSESSMENT_ID, stored.assessmentId());
		assertEquals(ProctoringCaptureType.IDENTITY_PHOTO, stored.captureType());
		assertEquals(CANDIDATE, stored.candidateEmail());
		assertEquals(JOB_PREFIX, stored.jobPrefix(), "the job is copied across so the admin listing needs no join");
		assertNotNull(stored.capturedAt());
		assertTrue(stored.imageUrl().endsWith("/image"));

		verify(storage).uploadFile(eq("proctoring"), eq("identity-photo/42.jpg"), any(MultipartFile.class));
	}

	@Test
	void retakingTheIdentityPhotoReplacesTheOneOnFile() {
		ExamProctoringCapture existing = ExamProctoringCapture.builder()
				.id(7L)
				.assessmentId(ASSESSMENT_ID)
				.candidateEmail(CANDIDATE)
				.captureType(ProctoringCaptureType.IDENTITY_PHOTO)
				.frameIndex(0)
				.build();
		when(captures.findByAssessmentIdAndCaptureTypeAndFrameIndex(ASSESSMENT_ID,
				ProctoringCaptureType.IDENTITY_PHOTO, 0)).thenReturn(Optional.of(existing));

		ProctoringCaptureDto stored = service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID), CANDIDATE, null,
				jpeg("second attempt"), CANDIDATE);

		assertEquals(7L, stored.id(), "the existing row is updated rather than a second photo being added");
	}

	@Test
	void aCandidateCannotFileACaptureAgainstSomeoneElsesAttempt() {
		assertThrows(AccessDeniedException.class, () -> service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID),
				CANDIDATE, null, jpeg("photo"), "someone.else@example.com"));

		verify(storage, never()).uploadFile(anyString(), anyString(), any(MultipartFile.class));
	}

	@Test
	void theRequestBodyCannotClaimADifferentCandidateThanTheCaller() {
		assertThrows(AccessDeniedException.class, () -> service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID),
				"someone.else@example.com", null, jpeg("photo"), CANDIDATE));
	}

	@Test
	void anUnknownAttemptIsRejected() {
		when(assessments.findById(999L)).thenReturn(Optional.empty());

		assertThrows(ResourceNotFoundException.class,
				() -> service.saveIdentityPhoto("999", CANDIDATE, null, jpeg("photo"), CANDIDATE));
	}

	@Test
	void aNonNumericAssessmentIdIsRejected() {
		assertThrows(IllegalArgumentException.class,
				() -> service.saveIdentityPhoto("not-an-id", CANDIDATE, null, jpeg("photo"), CANDIDATE));
	}

	@Test
	void anEmptyPhotoIsRejected() {
		MultipartFile empty = new MockMultipartFile("photo", "photo.jpg", "image/jpeg", new byte[0]);

		assertThrows(IllegalArgumentException.class,
				() -> service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID), CANDIDATE, null, empty, CANDIDATE));
	}

	@Test
	void somethingThatIsNotAnImageIsRejected() {
		MultipartFile pdf = new MockMultipartFile("photo", "notes.pdf", "application/pdf",
				"%PDF".getBytes(StandardCharsets.UTF_8));

		assertThrows(IllegalArgumentException.class,
				() -> service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID), CANDIDATE, null, pdf, CANDIDATE));
	}

	@Test
	void aClientClockThatMakesNoSenseDoesNotCostUsThePhoto() {
		ProctoringCaptureDto stored = service.saveIdentityPhoto(String.valueOf(ASSESSMENT_ID), CANDIDATE,
				"yesterday afternoon", jpeg("photo"), CANDIDATE);

		assertNotNull(stored.capturedAt(), "an unusable client timestamp falls back to server time");
		verify(storage).uploadFile(anyString(), anyString(), any(MultipartFile.class));
	}

	@Test
	void aRoomScanIsStoredAtWhateverLengthTheClientSent() {
		List<MultipartFile> frames = List.of(jpeg("one"), jpeg("two"), jpeg("three"), jpeg("four"), jpeg("five"));

		List<ProctoringCaptureDto> stored = service.saveRoomScan(String.valueOf(ASSESSMENT_ID), CANDIDATE,
				"2026-08-08T09:15:30.000Z", frames, CANDIDATE);

		assertEquals(5, stored.size(), "the frame count is client-configurable, not fixed at eight");
		for (int i = 0; i < stored.size(); i++) {
			assertEquals(i, stored.get(i).frameIndex(), "frames keep their sweep order");
			assertEquals(ProctoringCaptureType.ROOM_SCAN_FRAME, stored.get(i).captureType());
		}

		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(storage, org.mockito.Mockito.times(5)).uploadFile(eq("proctoring"), keys.capture(),
				any(MultipartFile.class));
		assertEquals("room-scan/42/frame-0.jpg", keys.getAllValues().get(0));
		assertEquals("room-scan/42/frame-4.jpg", keys.getAllValues().get(4));
	}

	@Test
	void aRepeatedRoomScanClearsTheEarlierSweep() {
		service.saveRoomScan(String.valueOf(ASSESSMENT_ID), CANDIDATE, null, List.of(jpeg("one"), jpeg("two")),
				CANDIDATE);

		// Otherwise a shorter second sweep would leave a stale tail of frames from
		// the first one, and the reviewer would see a room that no longer exists.
		verify(captures).deleteByAssessmentIdAndCaptureType(ASSESSMENT_ID, ProctoringCaptureType.ROOM_SCAN_FRAME);
	}

	@Test
	void anAbsurdlyLongRoomScanIsRejected() {
		ReflectionTestUtils.setField(service, "maxRoomScanFrames", 2);
		List<MultipartFile> frames = List.of(jpeg("one"), jpeg("two"), jpeg("three"));

		assertThrows(IllegalArgumentException.class, () -> service.saveRoomScan(String.valueOf(ASSESSMENT_ID),
				CANDIDATE, null, frames, CANDIDATE));

		verify(captures, never()).deleteByAssessmentIdAndCaptureType(any(), any());
	}

	@Test
	void aSkippedRoomScanIsNotStoredAsAnEmptyOne() {
		assertThrows(IllegalArgumentException.class,
				() -> service.saveRoomScan(String.valueOf(ASSESSMENT_ID), CANDIDATE, null, List.of(), CANDIDATE));

		verify(captures, never()).findByAssessmentIdAndCaptureTypeAndFrameIndex(any(), any(), anyInt());
	}

	@Test
	void reviewersReadTheCapturesBackForAnAttempt() {
		ExamProctoringCapture capture = ExamProctoringCapture.builder()
				.id(3L)
				.assessmentId(ASSESSMENT_ID)
				.candidateEmail(CANDIDATE)
				.jobPrefix(JOB_PREFIX)
				.captureType(ProctoringCaptureType.IDENTITY_PHOTO)
				.frameIndex(0)
				.containerName("proctoring")
				.fileName("identity-photo/42.jpg")
				.contentType("image/jpeg")
				.sizeBytes(51_200L)
				.capturedAt(LocalDateTime.now())
				.build();
		when(captures.findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(ASSESSMENT_ID))
				.thenReturn(List.of(capture));

		List<ProctoringCaptureDto> found = service.getCaptures(ASSESSMENT_ID);

		assertEquals(1, found.size());
		assertEquals("/api/exam-proctoring/captures/3/image", found.get(0).imageUrl());
	}

	@Test
	void anAttemptWithNoCapturesReadsBackAsEmptyRatherThanFailing() {
		when(captures.findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(ASSESSMENT_ID)).thenReturn(List.of());

		// Whether a room scan was asked for at all is an environment setting, so
		// "nothing captured" has to be an ordinary answer for the reviewer's screen.
		assertTrue(service.getCaptures(ASSESSMENT_ID).isEmpty());
	}

	private Assessment assessment() {
		Assessment assessment = new Assessment();
		assessment.setId(ASSESSMENT_ID);
		assessment.setCandidateEmail(CANDIDATE);
		assessment.setJobPrefix(JOB_PREFIX);
		assessment.setAssessmentType(AssessmentType.APTITUDE);
		return assessment;
	}

	private MultipartFile jpeg(String content) {
		return new MockMultipartFile("photo", "photo.jpg", "image/jpeg", content.getBytes(StandardCharsets.UTF_8));
	}
}
