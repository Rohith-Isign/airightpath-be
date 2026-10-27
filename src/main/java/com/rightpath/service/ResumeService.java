package com.rightpath.service;

import java.io.IOException;

import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ResumeDownload;

public interface ResumeService {

    /**
     * Adds or replaces the resume on the candidate's application for a specific job.
     *
     * <p>Writes {@code JobApplicationForCandidate.resumeData} — the same source
     * view-resume serves and ATS scores — on the application identified by
     * {@code (jobPrefix, email)}, rather than the orphaned {@code Resume} table.
     * Does not re-run ATS screening.
     *
     * @param file      The resume file (PDF, DOC, DOCX).
     * @param email     The candidate's email.
     * @param jobPrefix The job prefix identifying which application to update.
     * @throws IOException If an error occurs reading the file.
     * @throws com.rightpath.exceptions.ResourceNotFoundException If no application
     *         exists for the given job prefix and candidate (surfaced as HTTP 404).
     */
    void saveResume(MultipartFile file, String email, String jobPrefix) throws IOException;

    /**
     * Replaces the resume the candidate submitted with their job application.
     *
     * <p>Updates {@code JobApplicationForCandidate.resumeData} — the same source
     * view-resume serves and ATS scores — on the candidate's most recent
     * resume-bearing application. The standalone {@code Resume} table is no longer
     * used for viewing and is not written here. Does not re-run ATS screening.
     *
     * @param file  The new resume file.
     * @param email The candidate's email.
     * @throws IOException If an error occurs reading the file.
     * @throws com.rightpath.exceptions.ResourceNotFoundException If the candidate has
     *         no application carrying a resume (surfaced as HTTP 404).
     */
    void updateResume(MultipartFile file, String email) throws IOException;

    /**
     * Retrieves the resume the candidate submitted with their job application, as a
     * downloadable payload (bytes + filename + content type).
     *
     * <p>Reads {@code JobApplicationForCandidate.resumeData} — the same source the
     * ATS engine scores — so a candidate who has an ATS score can always view their
     * resume. If the candidate has applied to several jobs, the most recently
     * submitted resume-bearing application is chosen deterministically.
     *
     * @param email the candidate's email
     * @return the resume payload for download
     * @throws com.rightpath.exceptions.ResourceNotFoundException if the candidate
     *         has no application carrying a resume (surfaced as HTTP 404)
     */
    ResumeDownload getResumeForDownload(String email);

    /**
     * Extracts plain text content from a resume's binary data.
     *
     * @param resumeData The binary resume content.
     * @return Extracted text content from the resume.
     */
    String extractText(byte[] resumeData);
    
    /*
     * ===== Update Logs =====
     * ✅ Added jobPrefix as a parameter in saveResume() to associate resume with specific job posts.
     * ✅ Added extractText(byte[] resumeData) method to enable content extraction (e.g., for ATS processing).
     * ✅ Enhanced all method-level JavaDocs for clarity and developer usability.
     */
}
