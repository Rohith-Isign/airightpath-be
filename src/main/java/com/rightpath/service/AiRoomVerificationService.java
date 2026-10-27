package com.rightpath.service;

public interface AiRoomVerificationService {

	/**
	 * Whether the room shot passes, and why not when it does not.
	 *
	 * <p>Was a bare boolean, so the model's own explanation — "no laptop screen
	 * visible", "a second person is in frame" — was read from the response and
	 * thrown away. Every rejection reached the candidate as the same
	 * "reposition the phone", which does not tell them what to reposition it
	 * for, and every outage reached them as the same thing again.</p>
	 */
	RoomVerificationResult verify(byte[] bytes);

	/**
	 * @param valid     whether the shot passed
	 * @param reason    what to fix, or what went wrong; never null
	 * @param checkable false when the check could not run at all, as opposed to
	 *                  running and rejecting — the candidate can act on the
	 *                  second and only waste time on the first
	 */
	record RoomVerificationResult(boolean valid, String reason, boolean checkable) {
		public static RoomVerificationResult pass() {
			return new RoomVerificationResult(true, "", true);
		}

		public static RoomVerificationResult reject(String reason) {
			return new RoomVerificationResult(false, reason, true);
		}

		public static RoomVerificationResult unavailable(String reason) {
			return new RoomVerificationResult(false, reason, false);
		}
	}

}
