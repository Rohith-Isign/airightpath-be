	package com.rightpath.controller;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.dto.BulkMailRequestDTO;
import com.rightpath.dto.JobApplicationForCandidateDTO;
import com.rightpath.dto.ScreeningRequestDTO;
import com.rightpath.dto.ScreeningResponseDTO;
import com.rightpath.dto.ScreeningResultDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.rbac.PermissionName;
import com.rightpath.service.JobApplicationForCandidateService;
import com.rightpath.service.impl.EmailServiceImpl;
import com.rightpath.util.BusinessSchedule;

@RestController
@RequestMapping("/api/job-applications")
@lombok.extern.slf4j.Slf4j
public class JobApplicationForCandidateController {

    @Autowired
    private JobApplicationForCandidateService applicationForCandidateService;
    
    @Autowired
    private JobApplicationForCandidateRepository applicationForCandidateRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private EmailServiceImpl emailService;

    /** Parses and validates scheduled slots against the business clock (IST). */
    @Autowired
    private BusinessSchedule businessSchedule;

    private static final String WS_TOPIC = "/queue/updates";
    private static final String WS_TYPE = "APPLICATION_UPDATE";

    // Utility to send real-time WebSocket notifications to a specific user
    private void sendWebSocketNotification(String email, String jobPrefix, String status, String message) {
        // No template where the controller is built by hand (unit tests use the
        // two-argument constructor). Nothing to notify through, and not an error.
        if (messagingTemplate == null) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(
                email, 
                WS_TOPIC,
                Map.of(
                    "type", WS_TYPE,
                    "jobPrefix", jobPrefix,
                    "status", status,
                    "message", message,
                    "timestamp", System.currentTimeMillis()
                )
            );
        } catch (Exception e) {
            log.warn("WebSocket notification failed", e);
        }
    }

    // Constructor injection for unit testing
    public JobApplicationForCandidateController(
        JobApplicationForCandidateRepository applicationForCandidateRepository,
        JobApplicationForCandidateService applicationForCandidateService
    ) {
        this.applicationForCandidateRepository = applicationForCandidateRepository;
        this.applicationForCandidateService = applicationForCandidateService;
    }

    /**
     * Submit a new job application with resume and metadata
     */
    @PostMapping(value = "/apply", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<?> applyJob(
        @RequestPart("jobApplication") String jobApplicationJson,
        @RequestPart("resume") MultipartFile resume) throws Exception {

        ObjectMapper objectMapper = new ObjectMapper();
        JobApplicationForCandidateDTO dto = objectMapper.readValue(jobApplicationJson, JobApplicationForCandidateDTO.class);
        dto.setResume(resume);
        applicationForCandidateService.applyForJob(dto);

        return ResponseEntity.ok("Job Application Submitted Successfully.");
    }

    /**
     * Update existing job application (resume optional)
     */
    @PatchMapping(value = "/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<?> updateJobApplication(
        @RequestPart("jobApplication") String jobApplicationJson,
        @RequestPart(value = "resume", required = false) MultipartFile resume) throws Exception {

        JobApplicationForCandidateDTO dto = objectMapper.readValue(jobApplicationJson, JobApplicationForCandidateDTO.class);
        if (resume != null && !resume.isEmpty()) {
            dto.setResume(resume);
        }

        applicationForCandidateService.updateJobApplicationByJobPrefixAndEmail(dto);
        return ResponseEntity.ok("Job application updated successfully.");
    }

    /**
     * Admin: Fetch all applications
     */
    @GetMapping("/getAllApplications")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getAllApplications() {
        return ResponseEntity.ok(applicationForCandidateService.getAllApplications());
    }

    /**
     * Candidate: Get all applications for a given email
     */
    @GetMapping("/{email}")
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getApplicationsByEmail(@PathVariable String email) {
        return ResponseEntity.ok(applicationForCandidateService.getApplicationsByEmail(email));
    }

    /**
     * Admin: Get applications by jobPrefix
     */
    @GetMapping("/byJobPrefix/{jobPrefix}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getApplicationsByJobPrefix(@PathVariable String jobPrefix) {
        return ResponseEntity.ok(applicationForCandidateService.getApplicationsByJobPrefix(jobPrefix));
    }

    /**
     * Filter candidates by jobPostId
     */
    @GetMapping("/filter/{jobPostId}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> filterCandidates(@PathVariable Long jobPostId) {
        return ResponseEntity.ok(applicationForCandidateService.filterCandidates(jobPostId));
    }

    /**
     * Filter candidates by jobPrefix.
     *
     * @deprecated This route screens (and rewrites the status of) every applicant on
     *             the job, despite being a GET. Use {@code POST /screen} instead: it
     *             can screen a chosen subset and reports what it did per candidate.
     */
    @Deprecated(since = "2026-08")
    @GetMapping("/filterByPrefix/{jobPrefix}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> filterCandidatesByPrefix(@PathVariable String jobPrefix) {
        return ResponseEntity.ok(applicationForCandidateService.filterCandidatesByPrefix(jobPrefix));
    }

    /**
     * Run ATS screening over a chosen set of candidates, or the whole job.
     *
     * <p>Body: {@code { "jobPrefix": "...", "emails": ["a@x.com", ...] }}. Omit
     * {@code emails} (or send an empty list) to screen every applicant.</p>
     *
     * <p>Screening only touches applications still in the screening phase. A candidate
     * who has reached EXAM_SENT or INTERVIEW_SCHEDULED is reported in the response as
     * skipped, with the reason, and is never walked back to the shortlist stage.</p>
     */
    @PostMapping("/screen")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<ScreeningResponseDTO> screenCandidates(@RequestBody ScreeningRequestDTO request) {
        if (request.getJobPrefix() == null || request.getJobPrefix().isBlank()) {
            throw new IllegalArgumentException("Job prefix is required.");
        }

        ScreeningResponseDTO response =
            applicationForCandidateService.screenCandidates(request.getJobPrefix(), request.getEmails());

        for (ScreeningResultDTO result : response.results()) {
            if (result.screened()) {
                sendWebSocketNotification(result.email(), request.getJobPrefix(), result.status(),
                    "Screening completed: " + result.status());
            }
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Admin: Get all applicants for a job
     */
    @GetMapping("/applicants/{jobPostId}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getApplicantsByJobPostId(@PathVariable Long jobPostId) {
        return ResponseEntity.ok(applicationForCandidateService.getApplicantsByJobPostId(jobPostId));
    }

    /**
     * Get specific application by jobPrefix and candidate email
     */
    @GetMapping("/byJobPrefixAndEmail/{jobPrefix}/{email}")
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<JobApplicationForCandidateDTO> getApplicationByJobPrefixAndEmail(
        @PathVariable String jobPrefix,
        @PathVariable String email) {

        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);

        if (applications.isEmpty()) {
            return ResponseEntity.ok().build();
        }

        return ResponseEntity.ok(convertToDTO(applications.get(0)));
    }

    private JobApplicationForCandidateDTO convertToDTO(JobApplicationForCandidate entity) {
        JobApplicationForCandidateDTO dto = new JobApplicationForCandidateDTO();

        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setExperience(entity.getExperience());
        dto.setAddress(entity.getAddress());
        dto.setJobRole(entity.getJobRole());
        dto.setResumeFileName(entity.getResumeFileName());
        dto.setUserEmail(entity.getUser().getEmail());
        dto.setJobPrefix(entity.getJobPost().getJobPrefix());
        dto.setEmail(entity.getUser().getEmail());
        dto.setStatus(entity.getStatus().toString());
        dto.setMobileNumber(entity.getMobileNumber());
        dto.setReferralId(entity.getReferralId());
        dto.setReferralName(entity.getReferralName());
        dto.setReferralStatus(entity.getReferralStatus() != null ? entity.getReferralStatus().name() : null);
        dto.setConfirmationStatus(entity.getConfirmationStatus());
        dto.setAcknowledgedStatus(entity.getAcknowledgedStatus());
        dto.setReconfirmationStatus(entity.getReconfirmationStatus());
        dto.setExamLinkStatus(entity.getExamLinkStatus());
        dto.setExamCompletedStatus(entity.getExamCompletedStatus());
        dto.setRejectionStatus(entity.getRejectionStatus());
        dto.setCreatedAt(entity.getCreatedAt());

        return dto;
    }

    /**
     * Recruiter: Update the referral verification status of an application.
     * referralStatus must be one of PENDING / VERIFIED / REJECTED (case-insensitive).
     */
    @PatchMapping("/referral-status")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<JobApplicationForCandidateDTO> updateReferralStatus(
        @RequestParam String jobPrefix,
        @RequestParam String email,
        @RequestParam String referralStatus) {

        JobApplicationForCandidateDTO updated =
            applicationForCandidateService.updateReferralStatus(jobPrefix, email, referralStatus);
        sendWebSocketNotification(email, jobPrefix, "REFERRAL_STATUS_UPDATED",
            "Referral status updated to " + updated.getReferralStatus());
        return ResponseEntity.ok(updated);
    }

    /**
     * Recruiter: Manually shortlist candidates without ATS screening.
     *
     * <p>Body: {@code { "jobPrefix": "...", "emails": [...], "override": false }}.
     * Only APPLIED candidates are shortlisted; others are returned in failed[].</p>
     *
     * <p>Set {@code override} to true for "Shortlist Anyway" — it reopens a REJECTED
     * application, which is otherwise terminal. This is the supported way back from a
     * rejection; it clears the finalised-rejection marker so the candidate can also be
     * re-screened afterwards.</p>
     */
    @PatchMapping("/shortlist")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> shortlistWithoutAts(@RequestBody BulkMailRequestDTO request) {
        String jobPrefix = request.getJobPrefix();

        if (jobPrefix == null || jobPrefix.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Job prefix is required"));
        }
        if (request.getEmails() == null || request.getEmails().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "At least one email is required"));
        }

        List<String> sent = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.shortlistCandidateWithoutAts(jobPrefix, email, request.isOverride());
                sendWebSocketNotification(email, jobPrefix, "SHORTLISTED", "Candidate shortlisted");
                sent.add(email);
            } catch (Exception e) {
                failed.add(Map.of("email", email, "reason", e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("sent", sent);
        body.put("failed", failed);
        body.put("sentCount", sent.size());
        body.put("failedCount", failed.size());

        if (failed.isEmpty()) {
            body.put("message", "Candidates shortlisted successfully.");
            return ResponseEntity.ok(body);
        }
        if (!sent.isEmpty()) {
            body.put("message", "Some candidates were shortlisted; others failed.");
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(body);
        }
        body.put("message", "No candidates were shortlisted.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * ATS Screening - Shortlisted Candidates.
     *
     * <p>Read-only: this lists the current shortlist, it does not screen. Call
     * {@code POST /screen} to (re)run screening.</p>
     */
    @GetMapping("/ats-screening/{jobPrefix}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getShortlistedCandidates(@PathVariable String jobPrefix) {
        return ResponseEntity.ok(applicationForCandidateService.getShortlistedCandidatesByPrefix(jobPrefix));
    }

    /**
     * ATS Screening - Rejected Candidates.
     *
     * <p>Read-only, like {@code /ats-screening}: opening the rejected list must not
     * re-score the job.</p>
     */
    @GetMapping("/ats-rejected/{jobPrefix}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<List<JobApplicationForCandidateDTO>> getRejectedCandidates(@PathVariable String jobPrefix) {
        return ResponseEntity.ok(applicationForCandidateService.getRejectedCandidatesByPrefix(jobPrefix));
    }

    /**
     * Send acknowledgement mail and update status (bulk support).
     *
     * <p>The requested slot is parsed and checked against the business clock
     * <em>before</em> the send loop starts, so a missing or past {@code dateTime}
     * fails the whole request with HTTP 400 and mails nobody.</p>
     */
    @PostMapping("/send-ack-mail")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendAckMail(@RequestBody BulkMailRequestDTO request) {
        List<String> errors = new ArrayList<>();
        String jobPrefix = request.getJobPrefix();

        if (request.getEmails() == null || request.getEmails().isEmpty()) {
            throw new IllegalArgumentException("At least one email is required.");
        }

        if (jobPrefix == null || jobPrefix.isBlank()) {
            throw new IllegalArgumentException("Job prefix is required.");
        }

        // dateTime is required for ack mail (the email states the exam schedule) and
        // must not already have passed. Throws IllegalArgumentException -> 400.
        LocalDateTime examSlot = businessSchedule.requireFutureSlot(request.getDateTime());

        boolean hasValidationError = false;
        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.sendAcknowledgementMailAndUpdateStatus(jobPrefix, email, examSlot);
                sendWebSocketNotification(email, jobPrefix, "CONFIRMATION_SENT", "Confirmation email sent to candidate");
            } catch (IllegalStateException e) {
                errors.add(email + ": " + e.getMessage());
                hasValidationError = true;
            } catch (Exception e) {
                errors.add(email + ": " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ResponseEntity.ok(Map.of("message", "Confirmation mail sent successfully."));
        }
        HttpStatus status = hasValidationError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
            .body(Map.of("message", "Some emails failed", "errors", errors));
    }

    /**
     * Candidate Acknowledges confirmation
     */
    @GetMapping("/acknowledge")
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<Map<String, String>> acknowledgeMail(
        @RequestParam String jobPrefix,
        @RequestParam String email) {

        String result = applicationForCandidateService.acknowledgeCandidate(jobPrefix, email);
        sendWebSocketNotification(email, jobPrefix, "ACKNOWLEDGED_BACK", "Candidate acknowledged the confirmation");
        return ResponseEntity.ok(Map.of("status", "success", "message", result));
    }

    /**
     * Send re-confirmation mail (bulk support)
     */
    @PostMapping("/send-reconfirmation-mail")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendReconfirmationMail(@RequestBody BulkMailRequestDTO request) {
        List<String> errors = new ArrayList<>();
        String jobPrefix = request.getJobPrefix();

        boolean hasValidationError = false;
        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.sendReconfirmationMail(jobPrefix, email);
                sendWebSocketNotification(email, jobPrefix, "RECONFIRMATION_SENT", "Reconfirmation mail sent with exam details");
            } catch (IllegalStateException e) {
                errors.add(email + ": " + e.getMessage());
                hasValidationError = true;
            } catch (Exception e) {
                errors.add(email + ": " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ResponseEntity.ok("Reconfirmation mail sent successfully.");
        }
        HttpStatus status = hasValidationError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
            .body(Map.of("message", "Some emails failed", "errors", errors));
    }

    /**
     * Send rejection mail (bulk support)
     */
    @PostMapping("/send-rejection-mail")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendRejectionMail(@RequestBody BulkMailRequestDTO request) {
        List<String> errors = new ArrayList<>();
        String jobPrefix = request.getJobPrefix();

        boolean hasValidationError = false;
        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.sendRejectionMail(jobPrefix, email);
                sendWebSocketNotification(email, jobPrefix, "REJECTED", "Application rejected with notification sent");
            } catch (IllegalStateException e) {
                errors.add(email + ": " + e.getMessage());
                hasValidationError = true;
            } catch (Exception e) {
                errors.add(email + ": " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ResponseEntity.ok("Rejection mail sent successfully.");
        }
        HttpStatus status = hasValidationError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
            .body(Map.of("message", "Some emails failed", "errors", errors));
    }

    /**
     * Update written test result (aptitude/programming)
     */
    @PostMapping("/update-written-test-status")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<String> updateWrittenTestStatus(
        @RequestParam String jobPrefix,
        @RequestParam String email,
        @RequestParam boolean isAptitudePassed,
        @RequestParam boolean isProgrammingPassed) {

        applicationForCandidateService.updateWrittenTestStatus(jobPrefix, email, isAptitudePassed, isProgrammingPassed);
        return ResponseEntity.ok("Written Test Status updated successfully.");
    }

    /**
     * Send success mail after evaluation (bulk support)
     */
    @PostMapping("/send-success-mail")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendSuccessMail(@RequestBody BulkMailRequestDTO request) {
        List<String> errors = new ArrayList<>();
        String jobPrefix = request.getJobPrefix();

        boolean hasValidationError = false;
        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.sendSuccessMail(jobPrefix, email);
                sendWebSocketNotification(email, jobPrefix, "SELECTED", "Candidate selected successfully");
            } catch (IllegalStateException e) {
                errors.add(email + ": " + e.getMessage());
                hasValidationError = true;
            } catch (Exception e) {
                errors.add(email + ": " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ResponseEntity.ok("Success email sent successfully.");
        }
        HttpStatus status = hasValidationError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
            .body(Map.of("message", "Some emails failed", "errors", errors));
    }

    /**
     * Send failure mail after evaluation (bulk support)
     */
    @PostMapping("/send-failure-mail")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendFailureMail(@RequestBody BulkMailRequestDTO request) {
        List<String> errors = new ArrayList<>();
        String jobPrefix = request.getJobPrefix();

        boolean hasValidationError = false;
        for (String email : request.getEmails()) {
            try {
                applicationForCandidateService.sendFailureMail(jobPrefix, email);
                sendWebSocketNotification(email, jobPrefix, "REJECTED", "Candidate rejected after evaluation");
            } catch (IllegalStateException e) {
                errors.add(email + ": " + e.getMessage());
                hasValidationError = true;
            } catch (Exception e) {
                errors.add(email + ": " + e.getMessage());
            }
        }

        if (errors.isEmpty()) {
            return ResponseEntity.ok("Failure email sent successfully.");
        }
        HttpStatus status = hasValidationError ? HttpStatus.BAD_REQUEST : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
            .body(Map.of("message", "Some emails failed", "errors", errors));
    }
    
    
    /**
     * Send exam link to candidates (bulk support).
     *
     * <p>{@code dateTime} is optional here — omitting it starts the exam window
     * immediately — but a supplied slot must not already have passed, and is
     * checked before any mail goes out.</p>
     */
    @PostMapping("/send-exam-link")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> sendExamLink(@RequestBody BulkMailRequestDTO request) {
        String jobPrefix = request.getJobPrefix();

        if (request.getEmails() == null || request.getEmails().isEmpty()) {
            throw new IllegalArgumentException("At least one email is required.");
        }

        // Throws IllegalArgumentException -> 400 before anyone is mailed.
        LocalDateTime examSlot = businessSchedule.optionalFutureSlot(request.getDateTime());

        List<String> sent = new ArrayList<>();
        List<Map<String, String>> failed = new ArrayList<>();

        for (String email : request.getEmails()) {
            try {
                // Only marks EXAM_SENT if an assessment exists and the email is sent
                // (enforced transactionally in the service).
                applicationForCandidateService.sendExamLink(jobPrefix, email, examSlot);
                sendWebSocketNotification(email, jobPrefix, "EXAM_SENT", "Exam link sent to candidate");
                sent.add(email);
            } catch (Exception e) {
                failed.add(Map.of("email", email, "reason", e.getMessage() == null ? "Unknown error" : e.getMessage()));
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("sent", sent);
        body.put("failed", failed);
        body.put("sentCount", sent.size());
        body.put("failedCount", failed.size());

        if (failed.isEmpty()) {
            body.put("message", "Exam link sent successfully.");
            return ResponseEntity.ok(body);
        }
        if (!sent.isEmpty()) {
            // Some succeeded, some failed.
            body.put("message", "Exam link sent to some candidates; others failed.");
            return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(body);
        }
        // Nothing was sent — typically because no assessment is assigned yet.
        body.put("message", "No exam links were sent. Assign an exam to these candidates first.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @PostMapping("/schedule-interview")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<?> scheduleInterview(
            @RequestParam String jobPrefix,
            @RequestParam String email) {

        JobApplicationForCandidate updated = applicationForCandidateService.scheduleInterview(jobPrefix, email);
        return ResponseEntity.ok(new JobApplicationForCandidateDTO(updated));
    }

}