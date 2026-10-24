package com.rightpath.controller;

import com.rightpath.service.AiRoomVerificationService;
import com.rightpath.service.MobileCaptureService;
import com.rightpath.service.MobileConnectionService;
import com.rightpath.service.MobilePairingService;
import com.rightpath.service.OpenAiVisionService;
import com.rightpath.service.VoiceInterviewService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile") 
public class MobileVerificationController {

    @Autowired
    private AiRoomVerificationService verificationService;

    @Autowired
    private OpenAiVisionService openAiVisionService;

    @Autowired
    private VoiceInterviewService voiceInterviewService;

    @Autowired
    private MobileConnectionService mobileConnectionService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MobilePairingService pairingService;

    @Autowired
    private MobileCaptureService captureService;

    /**
     * Keep the photo the phone just sent, against the interview its token was
     * registered for. These were checked and thrown away, so a reviewer had
     * nothing to look at afterwards.
     *
     * <p>Best effort and quiet: a phone whose token was never registered, or a
     * storage hiccup, must not stop the room check itself from answering.</p>
     */
    private void keepPhoto(String token, String kind, MultipartFile photo) {
        try {
            var pairing = pairingService.resolveOrNull(token);
            if (pairing != null) {
                captureService.saveForPairing(pairing.getInterviewScheduleId(), kind, photo);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(MobileVerificationController.class)
                    .warn("Could not keep a phone photo ({}): {}", kind, e.getMessage());
        }
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Checks the room shot the phone just took.
     *
     * <p>Returns why it failed as well as whether it did. The phone showed
     * "Verification failed. Please reposition the phone." for every outcome —
     * a second person in frame, a laptop screen out of shot, a blank photo and
     * an OpenAI outage all looked identical, and only some of those are
     * something the candidate can act on.</p>
     */
    @PostMapping("/verify-room")
    public ResponseEntity<Map<String, Object>> verifyRoom(@RequestParam String token,
                                                          @RequestParam("photo") MultipartFile photo) throws IOException {
        keepPhoto(token, "ROOM_PHOTO", photo);
        AiRoomVerificationService.RoomVerificationResult result =
                verificationService.verify(photo.getBytes());

        return ResponseEntity.ok(Map.of(
                "valid", result.valid(),
                "reason", result.reason(),
                // False means "we could not check", not "you failed" — the phone
                // offers a retry rather than telling them to move.
                "checkable", result.checkable()));
    }

    @PostMapping("/monitor")
    public ResponseEntity<Map<String, Object>> monitor(@RequestParam String token,
                                                      @RequestParam("photo") MultipartFile photo) throws IOException {
        keepPhoto(token, "MONITOR_FRAME", photo);
        String status;
        String reason;
        try {
            JsonNode node = objectMapper.readTree(openAiVisionService.analyzeRoom(photo.getBytes()));
            status = node.path("status").asText("");
            reason = node.path("reason").asText("");
        } catch (Exception e) {
            // A periodic check that could not run is not a finding. It used to answer 500, which the phone
            // logged as an error on every tick of an OpenAI outage.
            org.slf4j.LoggerFactory.getLogger(MobileVerificationController.class)
                    .warn("Room monitoring could not be run: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("status", "UNAVAILABLE", "reason", "The room check could not be run."));
        }

        if ("FAILED".equalsIgnoreCase(status)) {
            // Find desktop session for this token
            String desktopSession = mobileConnectionService.getDesktopSession(token);
            if (desktopSession != null) {
                // Notify desktop about the malpractice
                messagingTemplate.convertAndSendToUser(desktopSession, "/queue/mobile/warning",
                    Map.of("type", "malpractice", "reason", reason));
            }
        }

        return ResponseEntity.ok(Map.of("status", status, "reason", reason));
    }
}