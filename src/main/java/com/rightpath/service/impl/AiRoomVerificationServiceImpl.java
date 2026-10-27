package com.rightpath.service.impl;

import com.rightpath.service.AiRoomVerificationService;
import com.rightpath.service.OpenAiVisionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AiRoomVerificationServiceImpl implements AiRoomVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AiRoomVerificationServiceImpl.class);

    /** Smaller than any real photograph; below this it is a blank frame. */
    private static final int MIN_IMAGE_BYTES = 2048;

    @Autowired
    private OpenAiVisionService openAiVisionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RoomVerificationResult verify(byte[] imageBytes) {
        // A phone that fired the shutter before its camera was ready sends a
        // blank frame. Putting that through the vision model costs a call and
        // comes back "no person visible", which reads to the candidate as a
        // failed room rather than a failed photograph.
        if (imageBytes == null || imageBytes.length < MIN_IMAGE_BYTES) {
            return RoomVerificationResult.reject(
                    "The photo did not capture properly. Wait for the camera preview, then try again.");
        }

        String raw;
        try {
            raw = openAiVisionService.analyzeRoom(imageBytes);
        } catch (Exception e) {
            // Distinct from a rejection. A candidate can do nothing about an
            // outage, and telling them to reposition the phone sends them
            // round a loop that cannot succeed.
            log.error("Room verification could not be run", e);
            return RoomVerificationResult.unavailable(
                    "The room check is unavailable right now. Please try again in a moment.");
        }

        try {
            JsonNode node = objectMapper.readTree(stripCodeFence(raw));
            String status = node.path("status").asText("");
            String reason = node.path("reason").asText("");

            if ("VERIFIED".equalsIgnoreCase(status)) {
                return RoomVerificationResult.pass();
            }
            // The model's own words wherever it gave any. "Reposition the
            // phone" does not say what to reposition it for; "your laptop
            // screen is not visible" does.
            return RoomVerificationResult.reject(reason.isBlank()
                    ? "The room check did not pass. Make sure you and your screen are both in frame, and that you are alone."
                    : reason);
        } catch (Exception e) {
            // A reply that is not the JSON we asked for is our problem, not the
            // candidate's, and must not be reported to them as a failed room.
            log.error("Room verification returned something unreadable: {}", raw, e);
            return RoomVerificationResult.unavailable(
                    "The room check could not be read. Please try again in a moment.");
        }
    }

    /**
     * The JSON inside a reply, fenced or not.
     *
     * <p>The request asks for a JSON object and usually gets one, but a reply
     * wrapped in ```json turned a pass into an unreadable response — and the
     * candidate into somebody repositioning a phone that was already right.</p>
     */
    private String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        return trimmed.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("```\\s*$", "").trim();
    }
}
