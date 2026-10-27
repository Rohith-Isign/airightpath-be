package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.AssessmentContentDto;
import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.entity.Assessment;
import com.rightpath.enums.AssessmentType;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.ResultRepository;
import com.rightpath.service.EmailService;
import com.rightpath.service.StorageService;

/**
 * Per-question exam timing, from assignment through to the exam screen.
 *
 * <p>The exam clock is question count times the admin's allowance. Only the
 * allowance is authoritative here — the count travels with the assignment for
 * reporting, but the duration is recomputed from the stored paper when the exam
 * opens, so nothing typed at assign time can shorten a candidate's exam.</p>
 */
class AssessmentTimingTest {

	private static final String CANDIDATE = "candidate@example.com";
	private static final String JOB_PREFIX = "FIXT-TIMING-2026-001";
	private static final String PAPER_JSON = "[{\"question\":\"1 + 1?\"},{\"question\":\"2 + 2?\"}]";

	private AssessmentRepository assessments;
	private StorageService storage;
	private AssessmentServiceImpl service;

	@BeforeEach
	void setUp() {
		assessments = mock(AssessmentRepository.class);
		storage = mock(StorageService.class);
		ResultRepository results = mock(ResultRepository.class);
		EmailService email = mock(EmailService.class);
		JobApplicationForCandidateRepository applications = mock(JobApplicationForCandidateRepository.class);

		service = new AssessmentServiceImpl(assessments, results, email, applications, storage);
		ReflectionTestUtils.setField(service, "examPrefix", "exam");

		when(applications.findByJobPrefixAndEmail(anyString(), anyString())).thenReturn(List.of());
	}

	@Test
	void theAllowanceTheAdminChoseIsStoredPerAssessmentType() {
		AssignAssessmentDto dto = assignment();
		dto.setAptitudeMinutesPerQuestion(2);
		dto.setAptitudeQuestionCount(30);
		dto.setAptitudeEstimatedDurationMinutes(60);
		dto.setCodingMinutesPerQuestion(25);
		dto.setCodingQuestionCount(2);
		dto.setCodingEstimatedDurationMinutes(50);

		service.assignAssessment(dto, JOB_PREFIX);

		Assessment aptitude = savedAssessment(AssessmentType.APTITUDE);
		assertEquals(2, aptitude.getMinutesPerQuestion());
		assertEquals(30, aptitude.getQuestionCount());
		assertEquals(60, aptitude.getEstimatedDurationMinutes());

		Assessment coding = savedAssessment(AssessmentType.CODING);
		assertEquals(25, coding.getMinutesPerQuestion(), "a coding problem is worth far more than an aptitude question");
		assertEquals(2, coding.getQuestionCount());
		assertEquals(50, coding.getEstimatedDurationMinutes());
	}

	@Test
	void anOlderClientThatSendsNoTimingLeavesTheAllowanceUnset() {
		service.assignAssessment(assignment(), JOB_PREFIX);

		Assessment aptitude = savedAssessment(AssessmentType.APTITUDE);
		// Null is the signal the client reads as "use your default for this type",
		// which is exactly how the exam behaved before per-question timing existed.
		assertNull(aptitude.getMinutesPerQuestion());
		assertNull(aptitude.getQuestionCount());
		assertNull(aptitude.getEstimatedDurationMinutes());
	}

	@Test
	void timingIsSetOnlyOnTheTypeItWasSentFor() {
		AssignAssessmentDto dto = assignment();
		dto.setCodingQuestionPaper(null);
		dto.setAptitudeMinutesPerQuestion(1);

		service.assignAssessment(dto, JOB_PREFIX);

		assertEquals(1, savedAssessment(AssessmentType.APTITUDE).getMinutesPerQuestion());
	}

	@Test
	void theExamScreenReadsTheAllowanceBackWithThePaper() {
		Assessment assessment = storedAssessment();
		assessment.setMinutesPerQuestion(25);
		when(assessments.findById(11L)).thenReturn(Optional.of(assessment));

		AssessmentContentDto content = service.getAssessmentContent(11L);

		assertEquals(25, content.minutesPerQuestion());
		assertNull(content.durationMinutes(), "no fixed override was pinned for this assignment");
		assertEquals(2, content.questions().size());
		assertEquals(AssessmentType.APTITUDE, content.assessmentType());
		assertEquals(CANDIDATE, content.candidateEmail());
	}

	@Test
	void aFixedDurationOverrideIsCarriedThrough() {
		Assessment assessment = storedAssessment();
		assessment.setMinutesPerQuestion(1);
		assessment.setDurationMinutes(90);
		when(assessments.findById(11L)).thenReturn(Optional.of(assessment));

		AssessmentContentDto content = service.getAssessmentContent(11L);

		assertEquals(90, content.durationMinutes(), "a pinned duration wins outright over the per-question sum");
		assertEquals(1, content.minutesPerQuestion());
	}

	@Test
	void aLegacyAssessmentCarriesNoTimingAtAll() {
		when(assessments.findById(11L)).thenReturn(Optional.of(storedAssessment()));

		AssessmentContentDto content = service.getAssessmentContent(11L);

		assertNull(content.minutesPerQuestion());
		assertNull(content.durationMinutes());
		assertEquals(2, content.questions().size(), "the paper still serves; only the timing is absent");
	}

	@Test
	void aPaperHeldInStorageIsFetchedRatherThanWrittenBackOntoTheRow() {
		Assessment assessment = storedAssessment();
		assessment.setQuestionPaper(null);
		assessment.setContainerName("exam");
		assessment.setFileName("paper.json");
		assessment.setMinutesPerQuestion(3);
		when(assessments.findById(11L)).thenReturn(Optional.of(assessment));
		when(storage.downloadFile("exam", "paper.json")).thenReturn(PAPER_JSON.getBytes(StandardCharsets.UTF_8));

		AssessmentContentDto content = service.getAssessmentContent(11L);

		assertEquals(2, content.questions().size());
		assertEquals(3, content.minutesPerQuestion());
		// Reading must not fatten the row: this runs in a transaction, so assigning
		// the paper back onto the entity would flush it into the column every read.
		assertNull(assessment.getQuestionPaper());
	}

	@Test
	void anUnknownAssessmentIsReportedAsMissing() {
		when(assessments.findById(404L)).thenReturn(Optional.empty());

		assertThrows(ResourceNotFoundException.class, () -> service.getAssessmentContent(404L));
	}

	private AssignAssessmentDto assignment() {
		AssignAssessmentDto dto = new AssignAssessmentDto();
		dto.setCandidateEmails(List.of(CANDIDATE));
		dto.setUploadedBy("admin@example.com");
		dto.setStartTime(LocalDateTime.now().plusDays(1));
		dto.setDeadline(LocalDateTime.now().plusDays(2));
		dto.setJobPrefix(JOB_PREFIX);
		dto.setAptitudeQuestionPaper(paper("aptitude.json"));
		dto.setCodingQuestionPaper(paper("coding.json"));
		return dto;
	}

	private Assessment storedAssessment() {
		Assessment assessment = new Assessment();
		assessment.setId(11L);
		assessment.setAssessmentType(AssessmentType.APTITUDE);
		assessment.setCandidateEmail(CANDIDATE);
		assessment.setJobPrefix(JOB_PREFIX);
		assessment.setQuestionPaper(PAPER_JSON);
		return assessment;
	}

	private MultipartFile paper(String name) {
		return new MockMultipartFile(name, name, "application/json", PAPER_JSON.getBytes(StandardCharsets.UTF_8));
	}

	private Assessment savedAssessment(AssessmentType type) {
		ArgumentCaptor<Assessment> saved = ArgumentCaptor.forClass(Assessment.class);
		verify(assessments, atLeastOnce()).save(saved.capture());
		return saved.getAllValues().stream()
				.filter(a -> a.getAssessmentType() == type)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No " + type + " assessment was saved"));
	}

	@Test
	void assigningStillUploadsBothPapers() {
		service.assignAssessment(assignment(), JOB_PREFIX);

		verify(storage, atLeastOnce()).uploadFile(anyString(), anyString(), any(MultipartFile.class));
	}
}
