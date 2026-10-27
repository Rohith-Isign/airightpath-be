package com.rightpath.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Size of each status bucket for a given search / job-type filter, so the UI can
 * label its dropdown ({@code Active (12)}, {@code Expired (25)},
 * {@code All Status (37)}) without downloading every row.
 *
 * <p>Counts always describe the <em>whole</em> filtered result set, not just the
 * page being returned, and {@code all == active + expired}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobStatusCountsDTO {

    /** Postings matching search/jobType regardless of deadline. */
    private long all;

    /** Subset whose deadline is today or later, or absent. */
    private long active;

    /** Subset whose deadline has passed. */
    private long expired;
}
