package com.rightpath.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.entity.InterviewMobileCapture;
import com.rightpath.service.MobileCaptureService;
import com.rightpath.service.MobilePairingService;

/**
 * What the phone sends besides its recording: the room scan, and a photo when something is wrong in its view.
 *
 * <p>Open to the phone on the strength of its pairing token, which the candidate's signed-in browser registered
 * against one interview. Nothing here sends anything to an AI model: the photos are kept for a reviewer.</p>
 */
@RestController
@RequestMapping("/api/mobile")
public class MobileVerificationController {

    private static final Logger log = LoggerFactory.getLogger(MobileVerificationController.class);

    private static final int MAX_ROOM_SCAN_PHOTOS = 30;

    @Autowired
    private MobilePairingService pairingService;

    @Autowired
    private MobileCaptureService captureService;

    /**
     * The room scan: the photos the phone took while the candidate panned it round the room, kept as evidence for
     * the reviewer. A pairing that cannot be resolved (closed, expired, never registered) answers 403 and the
     * phone tells the candidate to scan the code again.
     */
    @PostMapping("/room-scan")
    public ResponseEntity<Map<String, Object>> roomScan(@RequestParam String token,
                                                        @RequestParam("photos") java.util.List<MultipartFile> photos) {
        var pairing = pairingService.resolve(token);
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("At least one room photo is required");
        }
        if (photos.size() > MAX_ROOM_SCAN_PHOTOS) {
            throw new IllegalArgumentException("A room scan holds at most " + MAX_ROOM_SCAN_PHOTOS + " photos");
        }
        for (MultipartFile photo : photos) {
            captureService.saveForPairing(pairing.getInterviewScheduleId(), InterviewMobileCapture.ROOM_PHOTO, photo);
        }
        pairingService.markVerified(pairing);
        pairingService.recordEvent(pairing, "mobile_room_scan", "Phone room scan saved (" + photos.size() + " photos)");
        return ResponseEntity.ok(Map.of("saved", photos.size(), "valid", true));
    }

    /**
     * A photo taken because something is wrong in the phone's view: a second person in frame, or nobody there.
     *
     * <p>The phone looks at the candidate and the computer together, so it sees what the computer's own camera
     * cannot. It decides when to send one; this keeps it, with the reason, and puts a line in the interview's
     * proctoring log. The count is capped per reason so a room that stays wrong cannot fill the store.</p>
     *
     * @param reason {@code EXTRA_PERSON} or {@code NO_CANDIDATE}
     */
    @PostMapping("/proctor-capture")
    public ResponseEntity<Map<String, Object>> proctorCapture(@RequestParam String token, @RequestParam String reason,
                                                              @RequestParam("photo") MultipartFile photo) {
        String kind = reason == null ? "" : reason.trim().toUpperCase();
        if (!InterviewMobileCapture.EXTRA_PERSON.equals(kind) && !InterviewMobileCapture.NO_CANDIDATE.equals(kind)) {
            throw new IllegalArgumentException("reason must be EXTRA_PERSON or NO_CANDIDATE");
        }
        var pairing = pairingService.resolve(token);
        pairingService.touchSeen(token);
        captureService.saveForPairing(pairing.getInterviewScheduleId(), kind, photo);
        boolean extra = InterviewMobileCapture.EXTRA_PERSON.equals(kind);
        pairingService.recordEvent(pairing, extra ? "mobile_extra_person" : "mobile_candidate_absent",
                extra ? "A second person was in the phone camera's view"
                      : "The candidate was not in the phone camera's view");
        log.info("Phone proctoring photo kept for interview {}: {}", pairing.getInterviewScheduleId(), kind);
        return ResponseEntity.ok(Map.of("saved", true));
    }
}
