package com.rightpath.dto;

import org.springframework.data.domain.Page;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Paginated job listing: a {@link PaginatedResponse} of {@link JobPostDTO} plus
 * the status-bucket sizes for the same search / job-type filter.
 *
 * <pre>
 * {
 *   "content": [ { ...JobPostDTO... } ],
 *   "totalElements": 137, "totalPages": 7, "page": 0, "size": 20,
 *   "counts": { "all": 37, "active": 12, "expired": 25 }
 * }
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
public class JobListingResponse extends PaginatedResponse<JobPostDTO> {

    /**
     * Bucket sizes for the current {@code search} / {@code jobType} filter,
     * ignoring {@code status} and paging. Also available on its own via
     * {@code GET /api/jobs/counts}.
     */
    private JobStatusCountsDTO counts;

    public JobListingResponse(Page<JobPostDTO> page, JobStatusCountsDTO counts) {
        super(page);
        this.counts = counts;
    }
}
