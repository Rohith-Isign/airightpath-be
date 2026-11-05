package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rightpath.service.EmailAsyncService;
import com.rightpath.service.JobPromptService;
import com.rightpath.service.OpenAiService;
import com.rightpath.service.RecordingUploadCoordinator;
import com.rightpath.service.StorageService;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;

class PresignRecordingPartTest {

    private StorageService storage;
    private RecordingUploadCoordinator uploads;
    private InterviewServiceImpl service;

    @BeforeEach
    void setUp() {
        storage = mock(StorageService.class);
        uploads = mock(RecordingUploadCoordinator.class);
        service = new InterviewServiceImpl(mock(OpenAiService.class), mock(CandidateInterviewScheduleRepository.class),
                mock(JobApplicationForCandidateRepository.class), null, storage, mock(JobPromptService.class),
                mock(EmailAsyncService.class));
        ReflectionTestUtils.setField(service, "recordingUploads", uploads);
        ReflectionTestUtils.setField(service, "interviewPrefix", "interview");
        ReflectionTestUtils.setField(service, "partUrlTtlMinutes", 15L);
        ReflectionTestUtils.setField(service, "partMaxBytes", 1_000L);
    }

    @Test
    void anOwnedOpenUploadGetsASignedUrlAndCountsAsActive() {
        when(storage.presignUploadPart(eq("interview"), eq("interview-7/rec.webm"), eq("u1"), eq(2), eq(500L), any()))
                .thenReturn("https://signed");

        assertEquals("https://signed", service.presignRecordingPart(7L, "interview-7/rec.webm", "u1", 2, 500));
        verify(uploads).touched("u1");
    }

    @Test
    void aBlobThatIsNotThisInterviewsIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-8/rec.webm", "u1", 1, 500));
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-7/../interview-8/x.webm", "u1", 1, 500));
        verify(storage, never()).presignUploadPart(any(), any(), any(), anyInt(), anyLong(), any());
    }

    @Test
    void anOutOfRangePartOrSizeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-7/r.webm", "u1", 0, 500));
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-7/r.webm", "u1", 10_001, 500));
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-7/r.webm", "u1", 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.presignRecordingPart(7L, "interview-7/r.webm", "u1", 1, 1_001));
    }

    @Test
    void anUploadTheServerAlreadyFinishedIsRefusedWith410() {
        doThrow(new ResponseStatusException(HttpStatus.GONE)).when(uploads).requireOpen("u1");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.presignRecordingPart(7L, "interview-7/r.webm", "u1", 1, 500));
        assertEquals(HttpStatus.GONE, e.getStatusCode());
        verify(storage, never()).presignUploadPart(any(), any(), any(), anyInt(), anyLong(), any());
    }
}
