package com.rightpath.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.AssignAssessmentDto;
import com.rightpath.dto.AssignmentReportDTO;
import com.rightpath.service.AssessmentService;

/**
 * What the assign endpoint accepts as an exam allowance.
 *
 * <p>The allowance is multiplied by the question count to set the exam clock, so
 * a value that slipped through wrong would either give a candidate no time at all
 * or an exam outliving its own deadline. A missing value is fine — it means "use
 * the default for this type" — but a nonsensical one is refused outright.</p>
 */
class AssignTimingValidationTest {

	private static final String START = "2026-09-01T09:00";
	private static final String DEADLINE = "2026-09-02T18:00";
	private static final String JOB_PREFIX = "FIXT-ASSIGN-2026-001";

	private AssessmentService assessmentService;
	private AssessmentController controller;

	@BeforeEach
	void setUp() {
		assessmentService = mock(AssessmentService.class);
		controller = new AssessmentController(assessmentService);

		// The endpoint now reports per-candidate delivery, so a stubbed assignment
		// has to hand back a report; these cases only care about what got validated.
		AssignmentReportDTO report = new AssignmentReportDTO();
		report.recordNotified("candidate@example.com");
		when(assessmentService.assignAssessment(any(AssignAssessmentDto.class), anyString())).thenReturn(report);
	}

	@Test
	void theSubmittedAllowanceReachesTheService() {
		ResponseEntity<Map<String, Object>> response = assign("2", "30", "60", "25", "2", "50");

		assertEquals(HttpStatus.OK, response.getStatusCode());

		ArgumentCaptor<AssignAssessmentDto> dto = ArgumentCaptor.forClass(AssignAssessmentDto.class);
		verify(assessmentService).assignAssessment(dto.capture(), anyString());
		assertEquals(2, dto.getValue().getAptitudeMinutesPerQuestion());
		assertEquals(30, dto.getValue().getAptitudeQuestionCount());
		assertEquals(60, dto.getValue().getAptitudeEstimatedDurationMinutes());
		assertEquals(25, dto.getValue().getCodingMinutesPerQuestion());
		assertEquals(2, dto.getValue().getCodingQuestionCount());
		assertEquals(50, dto.getValue().getCodingEstimatedDurationMinutes());
	}

	@Test
	void anAssignmentWithNoTimingIsStillAccepted() {
		ResponseEntity<Map<String, Object>> response = assign(null, null, null, null, null, null);

		assertEquals(HttpStatus.OK, response.getStatusCode());

		ArgumentCaptor<AssignAssessmentDto> dto = ArgumentCaptor.forClass(AssignAssessmentDto.class);
		verify(assessmentService).assignAssessment(dto.capture(), anyString());
		assertEquals(null, dto.getValue().getAptitudeMinutesPerQuestion());
	}

	@Test
	void aZeroAllowanceIsRefused() {
		ResponseEntity<Map<String, Object>> response = assign("0", null, null, null, null, null);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertEquals("error", response.getBody().get("status"));
		assertTrue(String.valueOf(response.getBody().get("message")).contains("aptitudeMinutesPerQuestion"));
		verify(assessmentService, never()).assignAssessment(any(), anyString());
	}

	@Test
	void anAllowanceLongerThanAnyExamIsRefused() {
		ResponseEntity<Map<String, Object>> response = assign(null, null, null, "601", null, null);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertTrue(String.valueOf(response.getBody().get("message")).contains("codingMinutesPerQuestion"));
	}

	@Test
	void somethingThatIsNotANumberIsRefused() {
		ResponseEntity<Map<String, Object>> response = assign("twenty-five", null, null, null, null, null);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertTrue(String.valueOf(response.getBody().get("message")).contains("whole number"));
	}

	@Test
	void aNegativeQuestionCountIsRefused() {
		ResponseEntity<Map<String, Object>> response = assign("1", "-4", null, null, null, null);

		assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
		assertTrue(String.valueOf(response.getBody().get("message")).contains("aptitudeQuestionCount"));
	}

	@Test
	void blankFieldsAreTreatedAsAbsentRatherThanInvalid() {
		ResponseEntity<Map<String, Object>> response = assign("", "  ", "", "", "", "");

		assertEquals(HttpStatus.OK, response.getStatusCode(), "an empty multipart field is a value nobody typed");
	}

	private ResponseEntity<Map<String, Object>> assign(String aptitudeMinutes, String aptitudeCount,
			String aptitudeEstimate, String codingMinutes, String codingCount, String codingEstimate) {

		return controller.assignAssessment(
				"candidate@example.com",
				START,
				DEADLINE,
				paper("aptitude.json"),
				paper("coding.json"),
				null,
				"admin@example.com",
				JOB_PREFIX,
				aptitudeMinutes,
				aptitudeCount,
				aptitudeEstimate,
				codingMinutes,
				codingCount,
				codingEstimate);
	}

	private MultipartFile paper(String name) {
		return new MockMultipartFile(name, name, "application/json", "[]".getBytes(StandardCharsets.UTF_8));
	}
}
