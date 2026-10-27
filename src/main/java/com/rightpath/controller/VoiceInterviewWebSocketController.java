package com.rightpath.controller;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.rightpath.dto.voice.VoiceAnswerRequest;
import com.rightpath.entity.ProctoringEvent;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.ProctoringEventRepository;
import com.rightpath.service.AudioTranscriptionService;
import com.rightpath.service.VoiceInterviewService;
import com.rightpath.util.ProctoringEventSeverity;
import com.rightpath.websocket.WebSocketAuthInterceptor;

@Controller
public class VoiceInterviewWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(VoiceInterviewWebSocketController.class);

    private static final int MAX_AUDIO_CHUNK_BYTES = 200 * 1024; // 200KB

    private final VoiceInterviewService voiceInterviewService;
    private final AudioTranscriptionService audioTranscriptionService;
    private final SimpMessagingTemplate messagingTemplate;
    private final CandidateInterviewScheduleRepository scheduleRepo;
    private final ProctoringEventRepository proctoringEventRepository;

    private final ConcurrentHashMap<Long, AtomicInteger> chunkCounters = new ConcurrentHashMap<>();

    // Deduplication: key = scheduleId + transcript hash, value = timestamp
    private final ConcurrentHashMap<String, Long> recentAnswers = new ConcurrentHashMap<>();

    public VoiceInterviewWebSocketController(
            VoiceInterviewService voiceInterviewService,
            AudioTranscriptionService audioTranscriptionService,
            SimpMessagingTemplate messagingTemplate,
            CandidateInterviewScheduleRepository scheduleRepo,
            ProctoringEventRepository proctoringEventRepository) {
        this.voiceInterviewService = voiceInterviewService;
        this.audioTranscriptionService = audioTranscriptionService;
        this.messagingTemplate = messagingTemplate;
        this.scheduleRepo = scheduleRepo;
        this.proctoringEventRepository = proctoringEventRepository;

        // Cleanup expired dedup entries every 5 minutes
        Thread cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(300_000); // 5 minutes
                    long cutoff = System.currentTimeMillis() - 60_000;
                    recentAnswers.entrySet().removeIf(e -> e.getValue() < cutoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "dedup-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    /**
     * Extract authenticated user email from WS session attributes.
     */
    private String getUserEmail(SimpMessageHeaderAccessor headerAccessor) {
        Map<String, Object> sessionAttrs = headerAccessor.getSessionAttributes();
        if (sessionAttrs == null) return null;
        return (String) sessionAttrs.get(WebSocketAuthInterceptor.USER_EMAIL_ATTR);
    }

    /**
     * Verify that the schedule belongs to the authenticated user.
     */
    private boolean verifyOwnership(Long scheduleId, SimpMessageHeaderAccessor headerAccessor) {
        String email = getUserEmail(headerAccessor);
        if (email == null) {
            log.warn("No authenticated email found for schedule {}", scheduleId);
            sendError(scheduleId, "Authentication required");
            return false;
        }
        boolean owned = scheduleRepo.findByIdAndEmail(scheduleId, email).isPresent();
        if (!owned) {
            log.warn("Ownership check failed: user {} does not own schedule {}", email, scheduleId);
            sendError(scheduleId, "Unauthorized: schedule does not belong to you");
        }
        return owned;
    }

    private void sendError(Long scheduleId, String message) {
        Map<String, Object> errorMsg = new HashMap<>();
        errorMsg.put("error", message);
        messagingTemplate.convertAndSend(
                "/topic/interview/" + scheduleId + "/transcription-error",
                errorMsg
        );
    }

    /**
     * Receive audio chunk from client for real-time transcription.
     * Audio is sent as base64-encoded JSON since SockJS only supports text frames.
     */
    @MessageMapping("/interview/{scheduleId}/audio-chunk")
    public void handleAudioChunk(@DestinationVariable Long scheduleId, @Payload Map<String, String> payload,
                                  SimpMessageHeaderAccessor headerAccessor) {
        if (!verifyOwnership(scheduleId, headerAccessor)) return;

        String base64Audio = payload.get("audio");
        if (base64Audio == null || base64Audio.isBlank()) {
            log.warn("Received empty audio chunk for schedule {}", scheduleId);
            return;
        }

        byte[] audioData = Base64.getDecoder().decode(base64Audio);

        // Item 2: Audio chunk size limit (200KB)
        if (audioData.length > MAX_AUDIO_CHUNK_BYTES) {
            log.warn("Audio chunk too large for schedule {}: {} bytes (max {})",
                    scheduleId, audioData.length, MAX_AUDIO_CHUNK_BYTES);
            sendError(scheduleId, "Audio chunk too large (max 200KB)");
            return;
        }

        log.info("Received audio chunk for schedule {}: {} bytes (base64 len={})",
                scheduleId, audioData.length, base64Audio.length());

        AtomicInteger counter = chunkCounters.computeIfAbsent(scheduleId, k -> new AtomicInteger(0));
        int chunkIndex = counter.incrementAndGet();

        audioTranscriptionService.transcribeAsync(scheduleId, audioData, chunkIndex);
    }

    /**
     * Process submitted answer and generate next question.
     */
    @MessageMapping("/interview/{scheduleId}/submit-answer")
    public void handleSubmitAnswer(@DestinationVariable Long scheduleId, @Payload VoiceAnswerRequest request,
                                    SimpMessageHeaderAccessor headerAccessor) {
        if (!verifyOwnership(scheduleId, headerAccessor)) return;

        log.info("Received {} for schedule {}: {} chars",
                request.isSkipped() ? "skipped question" : "answer submission",
                scheduleId,
                request.getTranscript() != null ? request.getTranscript().length() : 0);

        // Item 5: Message deduplication — skip dedup check for skipped questions
        // (empty transcript would hash-collide across consecutive skips)
        if (!request.isSkipped() && request.getTranscript() != null) {
            String dedupKey = scheduleId + ":" + request.getTranscript().hashCode();
            Long lastSeen = recentAnswers.get(dedupKey);
            if (lastSeen != null && System.currentTimeMillis() - lastSeen < 60_000) {
                log.warn("Duplicate answer detected for schedule {}, ignoring", scheduleId);
                return;
            }
            recentAnswers.put(dedupKey, System.currentTimeMillis());
        }

        try {
            voiceInterviewService.processVoiceAnswer(scheduleId, request);
        } catch (Exception e) {
            log.error("Error processing answer for schedule {}", scheduleId, e);
            sendError(scheduleId, "Failed to process answer: " + e.getMessage());
        }
    }

    /**
     * Handle candidate interrupt (started speaking while AI is responding).
     */
    @MessageMapping("/interview/{scheduleId}/interrupt")
    public void handleInterrupt(@DestinationVariable Long scheduleId, @Payload Map<String, String> payload,
                                 SimpMessageHeaderAccessor headerAccessor) {
        if (!verifyOwnership(scheduleId, headerAccessor)) return;
        log.info("Interrupt received for schedule {}: {}", scheduleId, payload.get("reason"));
    }

    /**
     * Record proctoring events from the client.
     */
    @MessageMapping("/interview/{scheduleId}/proctoring-event")
    public void handleProctoringEvent(@DestinationVariable Long scheduleId, @Payload Map<String, String> payload,
                                       SimpMessageHeaderAccessor headerAccessor) {
        if (!verifyOwnership(scheduleId, headerAccessor)) return;

        String eventType = payload.get("type");
        String details = payload.get("details");

        boolean countsAsWarning = ProctoringEventSeverity.countsAsWarning(eventType);

        if (countsAsWarning) {
            log.warn("Proctoring event for schedule {}: {} - {}", scheduleId, eventType, details);
        } else {
            // Not a warning, so not logged as one. An upload audit at WARN
            // made every clean interview look like it had something wrong
            // with it.
            log.info("Proctoring record for schedule {}: {} - {}", scheduleId, eventType, details);
        }

        // Item 15: Save proctoring event to database. Saved whatever its
        // severity — the recording audit is only useful because it is kept.
        try {
            ProctoringEvent event = ProctoringEvent.builder()
                    .schedule(scheduleRepo.getReferenceById(scheduleId))
                    .eventType(eventType)
                    .details(details)
                    .build();
            proctoringEventRepository.save(event);
        } catch (Exception e) {
            log.error("Failed to save proctoring event for schedule {}", scheduleId, e);
        }

        // Only an actual violation moves the count that fails the interview.
        // This used to run for every event, so a candidate collected a warning
        // for each recording that uploaded *successfully*.
        if (!countsAsWarning) {
            return;
        }

        try {
            boolean terminated = voiceInterviewService.handleWarning(scheduleId);

            if (terminated) {
                messagingTemplate.convertAndSend(
                        "/topic/interview/" + scheduleId + "/response-complete",
                        Map.of("isComplete", true, "terminated", true, "reason", "Maximum warnings exceeded")
                );
            }
        } catch (Exception e) {
            log.error("Error handling proctoring event for schedule {}", scheduleId, e);
        }
    }
}
