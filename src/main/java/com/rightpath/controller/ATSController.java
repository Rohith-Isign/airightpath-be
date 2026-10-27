package com.rightpath.controller;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.AtsResultDto;
import com.rightpath.service.impl.ATSServiceImpl;
import com.rightpath.service.impl.EmailServiceImpl;

@RestController
@RequestMapping("/api")
public class ATSController {

    private static final Logger logger = LoggerFactory.getLogger(ATSController.class);
    private final ATSServiceImpl atsService;
    private final EmailServiceImpl emailService;

    public ATSController(ATSServiceImpl atsService, EmailServiceImpl emailService) {
        this.atsService = atsService;
        this.emailService = emailService;
    }

    /**
     * Uploads a single resume and a job description, and returns the matching score.
     * 
     * @param resumeFile       The uploaded resume file.
     * @param jobDescription   The job description content.
     * @return                 JSON response containing match score or error.
     */
    @PostMapping("/upload-single-resumes")
    public ResponseEntity<?> uploadFiles(
            @RequestParam("resume") MultipartFile resumeFile,
            @RequestParam("jobDescription") String jobDescription) throws Exception {

        logger.info("Received single resume for matching with job description.");

        if (jobDescription == null || jobDescription.trim().isEmpty()) {
            logger.warn("Job description is missing or empty.");
            Map<String, String> error = new HashMap<>();
            error.put("error", "Job description cannot be empty.");
            return ResponseEntity.badRequest().body(error);
        }

        double score = atsService.processFiles(resumeFile, jobDescription);
        logger.info("Resume processed successfully. Match score: {}", score);

        Map<String, Object> response = new HashMap<>();
        response.put("score", score);
        return ResponseEntity.ok(response);
    }

    /** Shortlisting threshold (percentage) for batch resume screening. */
    private static final double MATCH_THRESHOLD = 70.0;

    /**
     * Uploads multiple resumes and a job description and screens each one.
     * Returns every resume with its ATS score and whether it met the threshold,
     * so the client can display scores (not just the matched filenames).
     *
     * @param resumes          Array of resumes to process.
     * @param jobDescription   The job description content.
     * @return                 One {@link AtsResultDto} per uploaded resume.
     */
    @PostMapping("/upload-multiple-resumes")
    @PreAuthorize("hasAuthority('ATS_UPLOAD_MULTI')")
    public ResponseEntity<List<AtsResultDto>> uploadFiles(
            @RequestParam("resumes") MultipartFile[] resumes,
            @RequestParam("jobDescription") String jobDescription) throws Exception {

        if (jobDescription == null || jobDescription.trim().isEmpty()) {
            throw new IllegalArgumentException("Job description cannot be empty.");
        }

        logger.info("Received {} resumes for batch processing.", resumes.length);
        List<AtsResultDto> results = new ArrayList<>();

        for (MultipartFile resume : resumes) {
            AtsResultDto result = atsService.screenResume(resume, jobDescription, MATCH_THRESHOLD);
            logger.debug("Processed resume '{}'. email: {} score: {} matched: {}",
                    result.fileName(), result.email(), result.score(), result.matched());
            results.add(result);
        }

        long matchedCount = results.stream().filter(AtsResultDto::matched).count();
        logger.info("Batch processing completed. {} of {} resumes matched above threshold.",
                matchedCount, results.size());
        return ResponseEntity.ok(results);
    }

    /**
     * Sends a rejection email to a candidate.
     * Currently commented out for future use.
     */
//    @PostMapping("/send-rejection")
//    @PreAuthorize("hasRole('ROLE_ADMIN')")
//    public ResponseEntity<Map<String, String>> sendRejectionEmail(@RequestBody Map<String, String> payload) {
//        String toEmail = payload.get("toEmail");
//        String firstName = payload.get("firstName");
//        String lastName = payload.get("lastName");
//
//        Map<String, String> response = new HashMap<>();
//
//        try {
//            logger.info("Sending rejection email to: {}", toEmail);
//            emailService.sendRejectionEmail(toEmail, firstName, lastName);
//            response.put("message", "Rejection email sent to " + toEmail);
//            return ResponseEntity.ok(response);
//        } catch (Exception e) {
//            logger.error("Failed to send rejection email to {}: {}", toEmail, e.getMessage());
//            response.put("error", "Failed to send email: " + e.getMessage());
//            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
//        }
//    }
}
