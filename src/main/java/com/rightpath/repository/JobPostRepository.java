package com.rightpath.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;

/**
 * JPA Repository for {@link JobPost} entities and related operations.
 * 
 * <p>Provides efficient data access methods for:
 * <ul>
 *   <li>Job post management</li>
 *   <li>Job application tracking</li>
 *   <li>Job prefix lookups</li>
 * </ul>
 * 
 * <p>Includes both derived and custom JPQL queries with JOIN FETCH optimizations
 * to prevent N+1 query problems.
 *
 * <p>Extends {@link JpaSpecificationExecutor} so the paginated listing can build
 * its search / job-type / status predicates dynamically — see
 * {@link JobPostSpecifications}.
 */
public interface JobPostRepository extends JpaRepository<JobPost, Long>, JpaSpecificationExecutor<JobPost> {

    /**
     * Retrieves the most recently created job post.
     * 
     * @return Optional containing the latest job post by ID
     * 
     * @implNote Uses Spring Data's derived query method with ordering
     * @apiNote Useful for dashboard displays and recent activity feeds
     */
    Optional<JobPost> findTopByOrderByIdDesc();

    /**
     * Finds a job post with its associated applications in a single query.
     * 
     * @param jobId The ID of the job post
     * @return Optional containing job post with applications
     * 
     * @implNote Uses JOIN FETCH to load applications eagerly
     * @see JobPost#jobApplications
     */
    @Query("SELECT jp FROM JobPost jp JOIN FETCH jp.jobApplications WHERE jp.id = :jobId")
    Optional<JobPost> findJobPostWithApplications(@Param("jobId") Long jobId);

    /**
     * Finds a job application with its associated job post data.
     * 
     * @param id The application ID
     * @return Optional containing application with job post details
     * 
     * @implNote Eagerly fetches job post to avoid proxy objects
     * @apiNote Useful when displaying application details
     */
    @Query("SELECT a FROM JobApplicationForCandidate a JOIN FETCH a.jobPost WHERE a.id = :id")
    Optional<JobApplicationForCandidate> findByIdWithJobPost(@Param("id") Long id);

    /**
     * Finds a job post by its unique prefix identifier.
     * 
     * @param jobPrefix The job's prefix string
     * @return Optional containing matching job post
     * 
     * @implNote Uses Spring Data's property traversal
     * @apiNote Prefixes are typically used in candidate-facing URLs
     */
    Optional<JobPost> findByJobPrefix(String jobPrefix);

    /**
     * Lists every distinct {@code jobType} value stored, with how many postings
     * use it.
     *
     * <p>The column is free text, so the same logical type can be present in
     * several spellings ({@code "Full-Time"}, {@code "full time"}). Returning the
     * occurrence count lets the service pick the most common spelling as the
     * canonical label for the type dropdown.</p>
     *
     * @return one row per stored spelling, blank/null values excluded
     */
    @Query("SELECT jp.jobType AS jobType, COUNT(jp) AS occurrences FROM JobPost jp "
            + "WHERE jp.jobType IS NOT NULL AND TRIM(jp.jobType) <> '' "
            + "AND jp.deletedAt IS NULL "
            + "GROUP BY jp.jobType")
    List<JobTypeCount> findJobTypeCounts();

    /** Projection for {@link #findJobTypeCounts()}. */
    interface JobTypeCount {

        String getJobType();

        long getOccurrences();
    }
}