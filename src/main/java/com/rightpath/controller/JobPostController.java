package com.rightpath.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;

import com.rightpath.dto.JobPostDTO;
import com.rightpath.dto.JobPostDeletionDTO;
import com.rightpath.dto.JobPostSearchRequest;
import com.rightpath.dto.JobStatusCountsDTO;
import com.rightpath.entity.JobPost;
import com.rightpath.rbac.PermissionName;
import com.rightpath.service.JobPostService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for managing job postings and applications.
 * 
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Creating and retrieving job postings</li>
 *   <li>Applying to job positions</li>
 *   <li>Tracking application metrics</li>
 *   <li>Real-time job posting updates via WebSocket</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/jobs")
public class JobPostController {

    private final JobPostService jobPostService;
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public JobPostController(JobPostService jobPostService, 
                           SimpMessagingTemplate messagingTemplate) {
        this.jobPostService = jobPostService;
        this.messagingTemplate = messagingTemplate;
        log.info("JobPostController initialized with required dependencies");
    }

    /**
     * Creates a new job posting (Admin only).
     * 
     * @param dto Job posting details including:
     *            - Title
     *            - Description
     *            - Requirements
     *            - Other relevant fields
     * @return The created job posting with HTTP 200 status
     */
    @PostMapping("/post")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<JobPost> createJobPost(@Valid @RequestBody JobPostDTO dto) {
        log.info("Received request to create new job posting");
        JobPost created = jobPostService.createJobPost(dto);
        log.debug("Successfully created job posting with ID: {}", created.getId());

        // Notify subscribers via WebSocket
        messagingTemplate.convertAndSend("/topic/jobPosts", created);
        log.info("Broadcasted new job posting to WebSocket subscribers");

        return ResponseEntity.ok(created);
    }

    /**
     * Updates an existing job posting (Admin only).
     *
     * <p>The body is the same {@link JobPostDTO} {@code POST /post} accepts, validated
     * by the same rules, and is applied as a <strong>full replace</strong> of the
     * editable fields — send the whole object, not a patch. Two fields are not the
     * caller's to change:</p>
     * <ul>
     *   <li>{@code jobPrefix} — immutable; a differing value is rejected with
     *       {@code 400 JOB_PREFIX_IMMUTABLE} rather than renaming the posting, because
     *       applications, assessments, results and the public apply link key off it.
     *       Omitting it means "unchanged".</li>
     *   <li>{@code createdAt} — preserved. {@code updatedAt} / {@code updatedBy} are
     *       stamped from the request.</li>
     * </ul>
     *
     * <p>An expired posting may keep its own past {@code applicationDeadline} (fixing a
     * typo must not force a reopen), but moving the deadline to a different past date
     * returns {@code 400 JOB_DEADLINE_IN_PAST}.</p>
     *
     * @param id  id of the posting to edit, as returned in {@code JobPostDTO.id}
     * @param dto full replacement payload
     * @return the saved posting, in the same shape {@code POST /post} returns
     */
    @PutMapping("/post/{id}")
    @PreAuthorize("hasAuthority('JOB_POST_UPDATE')")
    public ResponseEntity<JobPost> updateJobPost(@PathVariable Long id, @Valid @RequestBody JobPostDTO dto) {
        log.info("Received request to update job posting {}", id);
        JobPost updated = jobPostService.updateJobPost(id, dto);

        // Same broadcast as create, so subscribed clients refresh their listing.
        messagingTemplate.convertAndSend("/topic/jobPosts", updated);
        log.info("Broadcasted updated job posting {} to WebSocket subscribers", id);

        return ResponseEntity.ok(updated);
    }

    /**
     * Retrieves job postings, either as a plain list or as a filtered page.
     *
     * <p><strong>No query parameters at all</strong> returns every posting as a
     * JSON array, newest first — the original behaviour, kept for clients that
     * have not migrated yet.</p>
     *
     * <p><strong>Any query parameter</strong> switches to the paginated form,
     * which returns {@code content} / {@code totalElements} / {@code totalPages} /
     * {@code page} / {@code size} plus a {@code counts} object sized for the
     * current {@code search} and {@code jobType} (see
     * {@link #getStatusCounts(String, String)}). Note that this form defaults to
     * <em>active</em> postings only.</p>
     *
     * @param page    zero-based page index (default 0)
     * @param size    page size (default 20, capped at 100)
     * @param sort    {@code field,direction}, e.g. {@code createdAt,desc}
     *                (default {@code applicationDeadline,asc}); {@code id} is
     *                always appended as a tiebreaker
     * @param status  {@code ACTIVE} | {@code EXPIRED} | {@code ALL} (default
     *                {@code ACTIVE}); postings with no deadline count as active
     * @param search  case-insensitive substring matched against job title, company,
     *                key skills, location and job prefix
     * @param jobType job type, compared ignoring case and separators so
     *                {@code full-time} matches {@code Full-Time} and {@code full time}
     * @return all postings (list) when no parameters are given, else a page of postings
     */
    @GetMapping("/getPost")
    @PreAuthorize("hasAuthority('JOB_POST_READ')")
    public ResponseEntity<Object> getAllJobs(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String jobType) {

        boolean parameterised = page != null || size != null || sort != null
                || status != null || search != null || jobType != null;
        if (!parameterised) {
            log.info("Received request for all job postings (no pagination)");
            return ResponseEntity.ok(jobPostService.getAllJobPosts());
        }

        log.info("Received request for job postings page={} size={} sort={} status={} search={} jobType={}",
                page, size, sort, status, search, jobType);
        return ResponseEntity.ok(jobPostService.searchJobPosts(JobPostSearchRequest.builder()
                .page(page)
                .size(size)
                .sort(sort)
                .status(status)
                .search(search)
                .jobType(jobType)
                .build()));
    }

    /**
     * Counts postings per status bucket, for labelling a status filter
     * ({@code Active (12)}, {@code Expired (25)}, {@code All Status (37)}).
     *
     * <p>Counts cover the whole filtered result set rather than a single page, and
     * are also embedded in the paginated {@code /getPost} response — this endpoint
     * exists for clients that need them without fetching rows.</p>
     *
     * @param search  optional free-text term, matched as in {@code /getPost}
     * @param jobType optional job type, matched as in {@code /getPost}
     * @return counts for {@code all}, {@code active} and {@code expired}
     */
    @GetMapping("/counts")
    @PreAuthorize("hasAuthority('JOB_POST_READ')")
    public ResponseEntity<JobStatusCountsDTO> getStatusCounts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String jobType) {
        log.info("Received request for job status counts search={} jobType={}", search, jobType);
        return ResponseEntity.ok(jobPostService.getStatusCounts(search, jobType));
    }

    /**
     * Lists the job types currently in use, for populating a filter dropdown.
     *
     * <p>Values differing only by case or separators ({@code "Full-Time"} vs
     * {@code "full time"}) collapse into one entry, so every option maps to exactly
     * one result set when passed back as {@code jobType}.</p>
     *
     * @return distinct job types sorted alphabetically, ignoring case
     */
    @GetMapping("/job-types")
    @PreAuthorize("hasAuthority('JOB_POST_READ')")
    public ResponseEntity<List<String>> getJobTypes() {
        log.info("Received request for distinct job types");
        return ResponseEntity.ok(jobPostService.getDistinctJobTypes());
    }

    /**
     * Deletes a job posting (Admin only).
     *
     * <p><strong>Deletion is soft.</strong> The posting is archived: it vanishes from
     * {@code getPost}, from the paginated listing (including {@code status=ALL}), from
     * the job-type dropdown and from the candidate apply path — but the row survives, so
     * applications, assessments, results and compiler submissions filed under its prefix
     * keep pointing at a real job and stay auditable. Nothing is cascaded and no
     * candidate history is destroyed, which is why a posting with applications deletes
     * just like an empty one instead of returning a conflict.</p>
     *
     * <p>The prefix stays reserved: it is unique and the archived row still holds it, so
     * it can never be handed to a future posting and make old applications ambiguous.</p>
     *
     * <p>Already-archived postings read as absent, so a repeated call returns
     * {@code 404 JOB_NOT_FOUND} rather than archiving twice.</p>
     *
     * @param id id of the posting to archive, as returned in {@code JobPostDTO.id}
     * @return what was archived, including the number of applications retained
     */
    @DeleteMapping("/post/{id}")
    @PreAuthorize("hasAuthority('JOB_POST_DELETE')")
    public ResponseEntity<JobPostDeletionDTO> deleteJobPost(@PathVariable Long id) {
        log.info("Received request to delete job posting {}", id);
        JobPostDeletionDTO deleted = jobPostService.deleteJobPost(id);

        // Same broadcast as create/update, so subscribed clients drop it from their list.
        messagingTemplate.convertAndSend("/topic/jobPosts", deleted);
        log.info("Broadcasted deletion of job posting {} ({}) to WebSocket subscribers",
                id, deleted.getJobPrefix());

        return ResponseEntity.ok(deleted);
    }

    /**
     * Submits a job application for a candidate.
     * 
     * @param jobId ID of the job to apply for
     * @param userEmail Email of the applicant
     * @return Application result message with:
     *         - HTTP 200 if successful
     *         - HTTP 404 if job not found
     *         - HTTP 500 for server errors
     */
    @PostMapping("/apply/{jobId}")
    @PreAuthorize("hasAuthority('JOB_APPLY')")
    public ResponseEntity<String> applyToJob(
            @PathVariable Long jobId, 
            @RequestParam String userEmail) {
        log.info("Received job application request from {} for job ID {}", userEmail, jobId);
        try {
            String result = jobPostService.applyToJob(jobId, userEmail);
            log.debug("Application result: {}", result);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid application attempt: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
        }
    }

    /**
     * Retrieves the number of applications for a specific job.
     * 
     * @param jobId ID of the job to query
     * @return Application count with:
     *         - HTTP 200 if successful
     *         - HTTP 404 if job not found
     *         - HTTP 500 for server errors
     */
    @GetMapping("/applications/count/{jobId}")
    @PreAuthorize("hasAuthority('JOB_APPLICATION_READ_ALL')")
    public ResponseEntity<Integer> getApplicationCount(@PathVariable Long jobId) {
        log.info("Received request for application count for job ID {}", jobId);
        try {
            int count = jobPostService.getApplicationCount(jobId);
            log.debug("Job ID {} has {} applications", jobId, count);
            return ResponseEntity.ok(count);
        } catch (IllegalArgumentException e) {
            log.warn("Job not found: {}", jobId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(0);
        }
    }
}