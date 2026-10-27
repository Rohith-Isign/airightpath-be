package com.rightpath.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Why an interview result is being taken off the results list.
 *
 * <p>A body rather than a query parameter, because a reason is prose: it ends
 * up in logs, in URLs and in browser history as a parameter, and the ones
 * people actually type ("duplicate of the 14th, candidate withdrew") belong in
 * none of those places.</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DeleteInterviewResultRequest {

	/** Required and non-blank; the service refuses an empty one. */
	private String reason;
}
