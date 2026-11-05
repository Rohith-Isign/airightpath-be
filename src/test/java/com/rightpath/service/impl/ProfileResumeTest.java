package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import com.rightpath.dto.ResumeDownload;
import com.rightpath.entity.CandidateProfileResume;
import com.rightpath.repository.CandidateProfileResumeRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;

/** A candidate who has applied to nothing can still save a resume, and read it back. */
@ExtendWith(MockitoExtension.class)
class ProfileResumeTest {

    @Mock
    private JobApplicationForCandidateRepository applications;

    @Mock
    private CandidateProfileResumeRepository profiles;

    @InjectMocks
    private ResumeServiceImpl service;

    @Test
    void savingWithNoApplicationStoresTheResumeOnTheProfile() throws Exception {
        when(applications.findResumeBearingApplicationsByEmail("a@x.com")).thenReturn(List.of());
        when(profiles.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.empty());

        service.updateResume(new MockMultipartFile("file", "cv.pdf", "application/pdf", new byte[] { 1, 2, 3 }),
                "a@x.com");

        ArgumentCaptor<CandidateProfileResume> saved = ArgumentCaptor.forClass(CandidateProfileResume.class);
        verify(profiles).save(saved.capture());
        assertEquals("cv.pdf", saved.getValue().getFileName());
        assertArrayEquals(new byte[] { 1, 2, 3 }, saved.getValue().getData());
    }

    @Test
    void withNoApplicationTheProfileResumeIsWhatIsServed() {
        when(applications.findResumeBearingApplicationsByEmail(any())).thenReturn(List.of());
        when(profiles.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(CandidateProfileResume.builder()
                .email("a@x.com").fileName("cv.pdf").contentType("application/pdf").data(new byte[] { 9 }).build()));

        ResumeDownload download = service.getResumeForDownload("a@x.com");

        assertEquals("cv.pdf", download.fileName());
        assertArrayEquals(new byte[] { 9 }, download.data());
    }
}
