package com.rightpath.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.MobileCaptureDto;
import com.rightpath.dto.ProctoringCaptureImage;
import com.rightpath.service.MobileCaptureService;
import com.rightpath.service.MobilePairingService;

/**
 * Stills from the candidate's paired phone, filed against the interview.
 * See {@link MobileCaptureService} for why the candidate's browser uploads them.
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewMobileCaptureController {

	private final MobileCaptureService mobileCaptureService;
	private final MobilePairingService mobilePairingService;

	public InterviewMobileCaptureController(MobileCaptureService mobileCaptureService,
			MobilePairingService mobilePairingService) {
		this.mobileCaptureService = mobileCaptureService;
		this.mobilePairingService = mobilePairingService;
	}

	/**
	 * Tie a phone's pairing token to this interview, so the phone can send its
	 * recording and photos without a login. Called by the candidate's browser as
	 * soon as it generates the token it shows as a QR code.
	 */
	@PostMapping("/{interviewScheduleId}/mobile-pairing")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Void> registerPairing(@PathVariable Long interviewScheduleId, @RequestParam String token,
			Authentication authentication) {
		mobilePairingService.register(interviewScheduleId, authentication.getName(), token);
		return ResponseEntity.ok().build();
	}

	/**
	 * Whether the phone behind this token has finished its room scan. The candidate's page asks this every few
	 * seconds, so the Begin button follows what the server has recorded and not whether a live message happened
	 * to arrive.
	 */
	@GetMapping("/{interviewScheduleId}/mobile-pairing/state")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<java.util.Map<String, Boolean>> pairingState(@PathVariable Long interviewScheduleId,
			@RequestParam String token, Authentication authentication) {
		return ResponseEntity.ok(java.util.Map.of("verified",
				mobilePairingService.isVerified(interviewScheduleId, authentication.getName(), token)));
	}

	/** The candidate is starting without a phone: close pairing for this interview. */
	@PostMapping("/{interviewScheduleId}/mobile-pairing/revoke")
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<Void> revokePairing(@PathVariable Long interviewScheduleId,
			@RequestParam(required = false) String token, Authentication authentication) {
		mobilePairingService.revoke(interviewScheduleId, authentication.getName(), token);
		return ResponseEntity.ok().build();
	}

	@PostMapping(value = "/{interviewScheduleId}/mobile-captures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasAuthority('INTERVIEW_ANSWER')")
	public ResponseEntity<MobileCaptureDto> upload(@PathVariable Long interviewScheduleId,
			@RequestParam("kind") String kind,
			@RequestParam(value = "capturedAt", required = false) String capturedAt,
			@RequestPart("photo") MultipartFile photo, Authentication authentication) {
		return ResponseEntity
				.ok(mobileCaptureService.save(interviewScheduleId, authentication.getName(), kind, capturedAt, photo));
	}

	@GetMapping("/{interviewScheduleId}/mobile-captures")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<List<MobileCaptureDto>> list(@PathVariable Long interviewScheduleId) {
		return ResponseEntity.ok(mobileCaptureService.list(interviewScheduleId));
	}

	@GetMapping("/mobile-captures/{captureId}/image")
	@PreAuthorize("hasAuthority('INTERVIEW_ASSIGN')")
	public ResponseEntity<byte[]> image(@PathVariable Long captureId) {
		ProctoringCaptureImage image = mobileCaptureService.loadImage(captureId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + image.fileName() + "\"")
				.body(image.bytes());
	}
}
