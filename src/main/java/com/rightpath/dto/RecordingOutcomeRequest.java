package com.rightpath.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What became of one of an interview's recordings.
 *
 * <p>Reported over HTTP rather than the interview WebSocket because the thing
 * most likely to have broken the upload is the thing that also breaks the
 * socket. An audit that only survives when nothing went wrong is not an
 * audit.</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecordingOutcomeRequest {

	/** {@code camera} or {@code screen}. */
	private String kind;

	/** True when the recording reached storage. */
	private boolean success;

	/** Bytes uploaded, or captured but not uploaded on a failure. */
	private long bytes;

	/** How many attempts it took, or were made before giving up. */
	private int attempts;

	/** How many parts the recording is in; 1 unless sharing was interrupted. */
	private int parts;

	/** Why it failed, in the candidate's client's words. Null on success. */
	private String failureReason;
}
