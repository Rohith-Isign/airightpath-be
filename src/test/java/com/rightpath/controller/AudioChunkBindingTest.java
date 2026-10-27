package com.rightpath.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.service.AudioTranscriptionService;

/**
 * The wire format of an answer's audio: a file upload, as the browser sends it.
 *
 * <p>The page's HTTP client defaults to JSON, and with that default a FormData is turned into a JSON object
 * instead of a file. Every chunk was then refused, which the page could only report as a microphone that heard
 * nothing. These pin down what the endpoint takes and what it turns away.</p>
 */
class AudioChunkBindingTest {

	private static final String CANDIDATE = "candidate@example.com";

	private AudioTranscriptionService transcription;
	private CandidateInterviewScheduleRepository schedules;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		transcription = mock(AudioTranscriptionService.class);
		schedules = mock(CandidateInterviewScheduleRepository.class);
		InterviewController controller = new InterviewController();
		ReflectionTestUtils.setField(controller, "audioTranscriptionService", transcription);
		ReflectionTestUtils.setField(controller, "scheduleRepo", schedules);
		mvc = MockMvcBuilders.standaloneSetup(controller).build();
	}

	@Test
	void aFileUploadIsAcceptedAndHandedToTranscription() throws Exception {
		when(schedules.findByIdAndEmail(7L, CANDIDATE)).thenReturn(Optional.of(new CandidateInterviewSchedule()));
		byte[] audio = "not really webm".getBytes(StandardCharsets.UTF_8);

		mvc.perform(multipart("/api/interview/voice/7/audio-chunk")
				.file(new MockMultipartFile("audio", "answer_0.webm", "audio/webm", audio))
				.part(new MockPart("chunkIndex", "3".getBytes(StandardCharsets.UTF_8)))
				.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a")))
				.andExpect(status().isAccepted());

		verify(transcription).transcribeAsync(eq(7L), eq(audio), eq(3));
	}

	@Test
	void aJsonBodyIsRefusedAndNothingIsTranscribed() throws Exception {
		// What the browser sent when the client serialised the FormData to JSON.
		mvc.perform(post("/api/interview/voice/7/audio-chunk")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"chunkIndex\":\"0\"}")
				.principal(new UsernamePasswordAuthenticationToken(CANDIDATE, "n/a")))
				.andExpect(status().isUnsupportedMediaType());

		verify(transcription, never()).transcribeAsync(any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}
}
