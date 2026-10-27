package com.rightpath.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.rightpath.util.TranscriptionResult;
import com.rightpath.util.TranscriptionSanitizer;

@Service
public class AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionService.class);

    private final OpenAiStreamingService openAiStreamingService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * The last text published for each interview, to catch the same words arriving again.
     *
     * <p>Fed a fan, a held breath or room tone chunk after chunk, Whisper tends to answer with the same
     * invented line each time ("I love you" a hundred times in one transcript). Real speech does not repeat
     * a whole four-second chunk word for word, so an identical text straight after itself is dropped.</p>
     */
    private final Map<Long, String> lastPublished = new ConcurrentHashMap<>();

    public AudioTranscriptionService(OpenAiStreamingService openAiStreamingService,
                                     SimpMessagingTemplate messagingTemplate) {
        this.openAiStreamingService = openAiStreamingService;
        this.messagingTemplate = messagingTemplate;
    }

    @Async("transcriptionExecutor")
    public void transcribeAsync(Long scheduleId, byte[] audioData, int chunkIndex) {
        log.info("Transcribing chunk {} for schedule {} ({} bytes)", chunkIndex, scheduleId, audioData.length);

        try {
            String filename = String.format("chunk_%d.webm", chunkIndex);
            TranscriptionResult result = openAiStreamingService.transcribe(audioData, filename);

            String comparable = result.getText() == null ? "" : TranscriptionSanitizer.comparable(result.getText());
            if (comparable.contains(" ") && comparable.equals(lastPublished.get(scheduleId))) {
                log.info("Dropped chunk {} for schedule {}: the same words as the chunk before (\"{}\")",
                        chunkIndex, scheduleId, result.getText());
                publishDone(scheduleId, chunkIndex);
                return;
            }

            if (result.getText() != null && !result.getText().isBlank()) {
                lastPublished.put(scheduleId, comparable);
                Map<String, Object> message = new HashMap<>();
                message.put("text", result.getText());
                message.put("words", result.getWords());
                message.put("duration", result.getDuration());
                message.put("chunkIndex", chunkIndex);
                message.put("done", true);

                messagingTemplate.convertAndSend(
                        "/topic/interview/" + scheduleId + "/transcription",
                        message
                );

                log.info("Transcribed chunk {} for schedule {}: \"{}\"", chunkIndex, scheduleId, result.getText());
            } else {
                log.warn("Chunk {} for schedule {} produced empty transcription (audio may be silence or corrupt)",
                        chunkIndex, scheduleId);
                publishDone(scheduleId, chunkIndex);
            }
        } catch (Exception e) {
            log.error("Error transcribing audio chunk {} for schedule {}", chunkIndex, scheduleId, e);

            Map<String, Object> errorMsg = new HashMap<>();
            errorMsg.put("error", "Transcription failed for chunk " + chunkIndex);
            errorMsg.put("chunkIndex", chunkIndex);

            messagingTemplate.convertAndSend(
                    "/topic/interview/" + scheduleId + "/transcription-error",
                    errorMsg
            );
            publishDone(scheduleId, chunkIndex);
        }
    }

    /**
     * Tell the page this chunk has been dealt with, though it produced no text.
     *
     * <p>With chunks that can hold a whole answer, the page waits for the last one before it submits. A chunk
     * of silence used to publish nothing, which left it waiting out its full timeout for words that were never
     * coming.</p>
     */
    private void publishDone(Long scheduleId, int chunkIndex) {
        Map<String, Object> message = new HashMap<>();
        message.put("text", "");
        message.put("words", java.util.List.of());
        message.put("duration", 0);
        message.put("chunkIndex", chunkIndex);
        message.put("done", true);
        messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/transcription", message);
    }
}
