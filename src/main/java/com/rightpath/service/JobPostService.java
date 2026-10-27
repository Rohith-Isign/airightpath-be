package com.rightpath.service;

import java.util.List;

import com.rightpath.dto.JobListingResponse;
import com.rightpath.dto.JobPostDTO;
import com.rightpath.dto.JobPostDeletionDTO;
import com.rightpath.dto.JobPostSearchRequest;
import com.rightpath.dto.JobStatusCountsDTO;
import com.rightpath.entity.JobPost;

/**
 * Service interface for managing job posts and application operations.
 */
public interface JobPostService {

    /**
     * Creates a new job post using the provided data transfer object.
     *
     * @param dto The job post data.
     * @return The created JobPost entity.
     * 
     * // Log Example: log.info("Creating job post with title: {}", dto.getTitle());
     */
    public JobPost createJobPost(JobPostDTO dto);

    /**
     * Replaces the editable fields of an existing job post.
     *
     * <p>{@code jobPrefix} and {@code createdAt} are preserved; {@code updatedAt}
     * and {@code updatedBy} are stamped. Every other field in the DTO overwrites
     * what is stored, so callers must send the full object rather than a patch.</p>
     *
     * @param id  The id of the posting to edit.
     * @param dto The full replacement payload.
     * @return The saved JobPost entity.
     * @throws com.rightpath.exceptions.JobPostNotFoundException if no posting has that id.
     * @throws com.rightpath.exceptions.JobPrefixImmutableException if the payload's
     *         prefix differs from the stored one.
     * @throws com.rightpath.exceptions.JobDeadlineInPastException if the payload moves
     *         the deadline to a date that has already passed.
     */
    public JobPost updateJobPost(Long id, JobPostDTO dto);

    /**
     * Archives a job post: it disappears from every listing and from the candidate
     * apply path, while the row itself stays.
     *
     * <p>Deletion is deliberately soft. Applications, assessments, results and compiler
     * submissions are filed under {@code jobPrefix}, so removing the row would either
     * orphan them or destroy candidate history; keeping it means dependent records
     * always point at a real job, remain auditable, and the prefix stays reserved (it is
     * unique, and the archived row still holds it).</p>
     *
     * <p>Idempotency: a posting that is already archived reads as absent, so a second
     * call reports it as not found.</p>
     *
     * @param id The id of the posting to archive.
     * @return What was archived, including how many applications were retained.
     * @throws com.rightpath.exceptions.JobPostNotFoundException if no live posting has that id.
     */
    public JobPostDeletionDTO deleteJobPost(Long id);

    /**
     * Converts a JobPost entity to its DTO representation.
     *
     * @param post The JobPost entity.
     * @return The JobPostDTO.
     * 
     * // Log Example: log.debug("Converting JobPost to DTO for jobPrefix: {}", post.getJobPrefix());
     */
    public JobPostDTO convertToDTO(JobPost post);

    /**
     * Retrieves all job posts, newest first (by id descending).
     *
     * @return A list of JobPostDTOs in descending order.
     */
    public List<JobPostDTO> getAllJobPosts();

    /**
     * Retrieves a filtered, sorted page of job posts together with the size of
     * each status bucket for the same search / job-type filter.
     *
     * <p>Defaults applied to an empty request: page 0, size 20 (capped at 100),
     * sort {@code applicationDeadline,asc} and status {@code ACTIVE}. The sort is
     * always given {@code id} as a final tiebreaker so consecutive pages cannot
     * overlap or drop rows.</p>
     *
     * @param request raw query parameters; defaulting and validation happen here.
     * @return A page of JobPostDTOs plus status counts.
     * @throws IllegalArgumentException if page/size/sort/status are unusable.
     */
    public JobListingResponse searchJobPosts(JobPostSearchRequest request);

    /**
     * Counts postings per status bucket for a search / job-type filter, across all
     * pages.
     *
     * @param search  optional free-text term; blank means no text filtering.
     * @param jobType optional job type; blank means no type filtering.
     * @return counts for all/active/expired.
     */
    public JobStatusCountsDTO getStatusCounts(String search, String jobType);

    /**
     * Lists the job types in use, for populating a filter dropdown.
     *
     * <p>Values that differ only by case or separators are collapsed into a single
     * entry (matching how {@code jobType} filtering compares values), represented
     * by their most commonly stored spelling.</p>
     *
     * @return distinct job types sorted alphabetically, ignoring case.
     */
    public List<String> getDistinctJobTypes();

    /**
     * Finds a job post by its unique job prefix.
     *
     * @param jobPrefix The job prefix identifier.
     * @return The JobPostDTO if found.
     * 
     * // Log Example: log.info("Searching for job post with prefix: {}", jobPrefix);
     */
    public JobPostDTO findByJobPrefix(String jobPrefix);

    /**
     * Applies a user to a job by job ID and user email.
     *
     * @param jobId     The ID of the job.
     * @param userEmail The applicant's email.
     * @return A success/failure message.
     * 
     * // Log Example: log.info("User {} applying to job ID: {}", userEmail, jobId);
     */
    public String applyToJob(Long jobId, String userEmail);

    /**
     * Retrieves the total number of applications submitted for a given job.
     *
     * @param jobId The job ID.
     * @return The application count.
     * 
     * // Log Example: log.debug("Counting applications for job ID: {}", jobId);
     */
    public int getApplicationCount(Long jobId);
}
