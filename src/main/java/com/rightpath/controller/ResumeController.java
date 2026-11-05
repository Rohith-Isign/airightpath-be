package com.rightpath.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ResumeDownload;
import com.rightpath.service.ResumeService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api")
public class ResumeController {

    @Autowired
    private ResumeService resumeService;

    // ===================================================================== //
    // Endpoint: Upload a resume for a user with jobPrefix                  //
    // ===================================================================== //
    /**
     * Uploads a resume for the specified user with a jobPrefix.
     *
     * @param file      The resume file to be uploaded.
     * @param email     The email of the user.
     * @param jobPrefix The job prefix associated with this resume.
     * @return ResponseEntity with success or error message.
     */
    @PostMapping("/upload-resume")
    @PreAuthorize("hasAuthority('RESUME_UPLOAD')")
    public ResponseEntity<String> uploadResume(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jobPrefix") String jobPrefix,
            Authentication authentication) throws Exception {

        String email = authentication.getName();

        log.info("Resume upload by {} for job {}", email, jobPrefix);
        resumeService.saveResume(file, email, jobPrefix);

        return ResponseEntity.ok("Resume uploaded successfully.");
    }

    // ===================================================================== //
    // Endpoint: Update existing resume                                     //
    // ===================================================================== //
    /**
     * Updates the existing resume of the user.
     *
     * @param file  The new resume file.
     * @param email The email of the user.
     * @return ResponseEntity with success or error message.
     */
    @PutMapping("/update-resume")
    @PreAuthorize("hasAuthority('RESUME_UPDATE')")
    public ResponseEntity<Map<String, String>> updateResume(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "email", required = false) String ignoredEmail,
            Authentication authentication) throws Exception {
        Map<String, String> response = new HashMap<>();
        // The signed-in candidate's own address, never the one in the request: it was taken from the form, so any
        // candidate could replace another's resume by sending their email.
        String email = authentication.getName();
        log.info("Resume update for {}", email);
        resumeService.updateResume(file, email);
        response.put("message", "Resume updated successfully.");
        return ResponseEntity.ok(response);
    }

    // ===================================================================== //
    // Endpoint: View resume by user email                                  //
    // ===================================================================== //
    /**
     * Retrieves and downloads the resume of a user by their email.
     *
     * @param email The email of the user.
     * @return ResponseEntity containing the resume file.
     */
    @GetMapping("/view-resume/{email}")
    @PreAuthorize("hasAuthority('RESUME_VIEW')")
    public ResponseEntity<byte[]> viewResume(@PathVariable String email,
            @RequestParam(value = "jobPrefix", required = false) String jobPrefix) {
        // Serves the resume the candidate submitted with their application
        // (JobApplicationForCandidate.resumeData) — the same source the ATS engine
        // scores. A genuinely missing resume throws ResourceNotFoundException, which
        // the global handler maps to 404 (not the old misleading 400).
        log.info("Resume download requested for {}", email);
        // With a job, the resume sent with that application; without one, the candidate's latest.
        ResumeDownload resume = resumeService.getResumeForDownload(email, jobPrefix);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resume.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resume.fileName() + "\"")
                .body(resume.data());
    }

}
