package com.rightpath.service.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ResumeDownload;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.service.ResumeService;

@Service
public class ResumeServiceImpl implements ResumeService {

	@Autowired
	private JobApplicationForCandidateRepository applicationForCandidateRepository;

	private final Tika tika = new Tika();

	@Override
	public void saveResume(MultipartFile file, String email, String jobPrefix) throws IOException {
	    // "Upload Resume" adds/replaces the resume on the candidate's application for
	    // THIS job — the same source view-resume serves and ATS scores
	    // (JobApplicationForCandidate.resumeData) — rather than the orphaned Resume
	    // table. Does not re-run ATS screening; the next screening pass picks it up.
	    JobApplicationForCandidate application = applicationForCandidateRepository
	            .findByJobPrefixAndEmail(jobPrefix, email).stream()
	            .findFirst()
	            .orElseThrow(() -> new ResourceNotFoundException(
	                    "No application found for job prefix '" + jobPrefix + "' and candidate: " + email));

	    application.setResumeFileName(file.getOriginalFilename());
	    application.setContentType(file.getContentType());
	    application.setResumeData(file.getBytes());
	    applicationForCandidateRepository.save(application);
	}


	/**
	 * Updates an existing resume file for a user identified by their email.
	 *
	 * @param file  The updated resume file.
	 * @param email The email of the user whose resume needs to be updated.
	 * @return The updated Resume entity.
	 * @throws IOException If there is an issue reading the file data.
	 */
	@Override
	public void updateResume(MultipartFile file, String email) throws IOException {
		// Update the resume the candidate applied with — the same source view-resume
		// serves and ATS scores (JobApplicationForCandidate.resumeData) — rather than
		// the orphaned standalone Resume table. Targets the most recent resume-bearing
		// application (the one view-resume returns). Does not re-run ATS screening; the
		// next screening pass will pick up the new resume.
		JobApplicationForCandidate application = applicationForCandidateRepository
				.findResumeBearingApplicationsByEmail(email).stream()
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException(
						"No application with a resume found for candidate: " + email));

		application.setResumeFileName(file.getOriginalFilename());
		application.setContentType(file.getContentType());
		application.setResumeData(file.getBytes());
		applicationForCandidateRepository.save(application);
	}

	/**
	 * Retrieves the resume the candidate submitted with their job application, as a
	 * downloadable payload.
	 *
	 * <p>Reads {@code JobApplicationForCandidate.resumeData} — the same column the ATS
	 * engine scores — so any candidate with an ATS score can view their resume. When a
	 * candidate has applied to multiple jobs the choice is deterministic: the most
	 * recently submitted resume-bearing application (highest id) wins.
	 *
	 * @param email The candidate's email.
	 * @return The resume payload (bytes + filename + content type).
	 * @throws ResourceNotFoundException If no application carries a resume (HTTP 404).
	 */
	@Override
	public ResumeDownload getResumeForDownload(String email) {
		JobApplicationForCandidate application = applicationForCandidateRepository
				.findResumeBearingApplicationsByEmail(email).stream()
				.findFirst()
				.orElseThrow(() -> new ResourceNotFoundException("Resume not found for candidate: " + email));

		String fileName = (application.getResumeFileName() != null && !application.getResumeFileName().isBlank())
				? application.getResumeFileName()
				: "resume";
		String contentType = (application.getContentType() != null && !application.getContentType().isBlank())
				? application.getContentType()
				: MediaType.APPLICATION_OCTET_STREAM_VALUE;

		return new ResumeDownload(application.getResumeData(), fileName, contentType);
	}

	public String extractText(byte[] resumeData) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(resumeData)) {
            return tika.parseToString(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Error parsing resume data", e);
        }
    }

}