package com.rightpath.controller;

import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rightpath.dto.RecordingOutcomeRequest;
import com.rightpath.entity.InterviewMobilePairing;
import com.rightpath.service.InterviewService;
import com.rightpath.service.MobilePairingService;

/**
 * The paired phone sending its own recording, filed against the interview its
 * pairing token was registered for.
 *
 * <p>Open to the phone because the phone has no login; what authorises a
 * request here is a pairing token the signed-in candidate registered (see
 * {@link MobilePairingService}). The phone records its own camera and uploads it
 * from there, so the recording exists whether or not the live picture ever
 * reached the interview screen.</p>
 */
@RestController
@RequestMapping("/api/mobile")
public class MobileRecordingController {

	/** What the phone may put on the interview's record. Anything else is refused. */
	private static final Set<String> PHONE_EVENTS = Set.of("mobile_recording_started", "mobile_recording_not_started",
			"mobile_recording_issue");

	private final MobilePairingService pairingService;
	private final InterviewService interviewService;

	public MobileRecordingController(MobilePairingService pairingService, InterviewService interviewService) {
		this.pairingService = pairingService;
		this.interviewService = interviewService;
	}

	/** Whether this phone may still join: open, closed (the interview began without one) or unknown. */
	@org.springframework.web.bind.annotation.GetMapping("/pairing-status")
	public ResponseEntity<Map<String, String>> pairingStatus(@RequestParam String token) {
		return ResponseEntity.ok(Map.of("state", pairingService.state(token)));
	}

	@PostMapping("/recording-upload")
	public ResponseEntity<Map<String, String>> begin(@RequestParam String token,
			@RequestParam(required = false) String type) {
		InterviewMobilePairing pairing = pairingService.resolve(token);
		return ResponseEntity.ok(interviewService.beginRecordingUpload(pairing.getInterviewScheduleId(), "mobile", type));
	}

	@PostMapping(value = "/recording-upload/part", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
	public ResponseEntity<Void> part(@RequestParam String token, @RequestParam String uploadId,
			@RequestParam String blobName, @RequestParam int part, @RequestBody byte[] data) {
		InterviewMobilePairing pairing = pairingService.resolve(token);
		pairingService.accept(pairing, data.length);
		interviewService.uploadRecordingPart(pairing.getInterviewScheduleId(), blobName, uploadId, part, data);
		return ResponseEntity.ok().build();
	}

	@PostMapping("/recording-upload/complete")
	public String complete(@RequestParam String token, @RequestParam String uploadId, @RequestParam String blobName) {
		InterviewMobilePairing pairing = pairingService.resolve(token);
		return interviewService.completeRecordingUpload(pairing.getInterviewScheduleId(), "mobile", blobName, uploadId);
	}

	/** The phone saying its recording started, could not start, or hit a problem. */
	@PostMapping("/recording-event")
	public ResponseEntity<Void> event(@RequestParam String token, @RequestParam String type,
			@RequestParam(required = false) String details) {
		InterviewMobilePairing pairing = pairingService.resolve(token);
		if (!PHONE_EVENTS.contains(type)) {
			throw new IllegalArgumentException("Unknown phone event: " + type);
		}
		String text = details == null ? "" : details.trim();
		pairingService.recordEvent(pairing, type, text.length() > 500 ? text.substring(0, 500) : text);
		return ResponseEntity.ok().build();
	}

	/** How the phone's recording ended up, in the same words the laptop's recordings use. */
	@PostMapping("/recording-outcome")
	public ResponseEntity<Void> outcome(@RequestParam String token, @RequestBody RecordingOutcomeRequest request) {
		InterviewMobilePairing pairing = pairingService.resolve(token);

		StringBuilder details = new StringBuilder("mobile recording ");
		details.append(request.isSuccess() ? "saved" : "was not saved");
		details.append(" (").append(Math.round(request.getBytes() / 1024.0)).append(" KB");
		if (request.getParts() > 1) {
			details.append(", ").append(request.getParts()).append(" parts");
		}
		if (request.getAttempts() > 1) {
			details.append(", ").append(request.getAttempts()).append(" attempts");
		}
		details.append(')');
		if (!request.isSuccess() && request.getFailureReason() != null && !request.getFailureReason().isBlank()) {
			details.append(": ").append(request.getFailureReason().trim());
		}
		pairingService.recordEvent(pairing, request.isSuccess() ? "recording_uploaded" : "recording_upload_failed",
				details.toString());
		return ResponseEntity.ok().build();
	}
}
