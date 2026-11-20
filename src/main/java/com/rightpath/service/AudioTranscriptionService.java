package com.rightpath.service;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.rightpath.service.state.SharedStateStore;
import com.rightpath.util.TranscriptionResult;
import com.rightpath.util.TranscriptionSanitizer;

@Service
public class AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionService.class);

    private final OpenAiStreamingService openAiStreamingService;
    private final SimpMessagingTemplate messagingTemplate;

    private static final Duration LAST_PUBLISHED_TTL = Duration.ofHours(2);

    /**
     * Holds the last text published for each interview, to catch the same words arriving again. Shared
     * across pods because consecutive chunks of one interview need not reach the same replica.
     *
     * <p>Fed a fan, a held breath or room tone chunk after chunk, Whisper tends to answer with the same
     * invented line each time ("I love you" a hundred times in one transcript). Real speech does not repeat
     * a whole four-second chunk word for word, so an identical text straight after itself is dropped.</p>
     */
    private final SharedStateStore sharedState;

    /** Whether to check what language each chunk was really spoken in (see {@link #looksForeign}). */
    @org.springframework.beans.factory.annotation.Value("${openai.audio.detect-language:true}")
    private boolean detectLanguage = true;

    /** Counters for the answer being recorded: chunks checked, and chunks found not to be English. */
    public static String languageTotalKey(Long scheduleId) {
        return "lang-total:" + scheduleId;
    }

    public static String languageForeignKey(Long scheduleId) {
        return "lang-foreign:" + scheduleId;
    }

    public static String languageNameKey(Long scheduleId) {
        return "lang-name:" + scheduleId;
    }

    /** Set once the candidate has been warned during the answer being recorded, so the warning is not repeated. */
    public static String languageWarnedKey(Long scheduleId) {
        return "lang-warned:" + scheduleId;
    }

    /**
     * Whether a detection result is really another language. Two conditions, because detection on a few seconds of
     * accented English sometimes names another language (that is why transcription is pinned to English): the
     * language must not be English, and the text it heard must be mostly in a non-Latin script, which an English
     * answer misheard as another language rarely is.
     */
    static boolean looksForeign(String language, String text) {
        if (language == null || language.isBlank() || language.toLowerCase().startsWith("en")) {
            return false;
        }
        if (text == null) {
            return false;
        }
        long letters = text.codePoints().filter(Character::isLetter).count();
        if (letters < 8) {
            return false;
        }
        long nonLatin = text.codePoints()
                .filter(Character::isLetter)
                .filter(cp -> Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN)
                .count();
        return nonLatin * 2 >= letters;
    }

    /** The candidate's name, given to the speech model so it spells it the way it is on their account. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CandidateNameResolver nameResolver;

    public AudioTranscriptionService(OpenAiStreamingService openAiStreamingService,
                                     SimpMessagingTemplate messagingTemplate,
                                     SharedStateStore sharedState) {
        this.sharedState = sharedState;
        this.openAiStreamingService = openAiStreamingService;
        this.messagingTemplate = messagingTemplate;
    }

    private static String lastPublishedKey(Long scheduleId) {
        return "last-transcript:" + scheduleId;
    }

    @Async("transcriptionExecutor")
    public void transcribeAsync(Long scheduleId, byte[] audioData, int chunkIndex) {
        log.info("Transcribing chunk {} for schedule {} ({} bytes)", chunkIndex, scheduleId, audioData.length);

        try {
            String filename = String.format("chunk_%d.webm", chunkIndex);
            String hint = nameResolver == null ? null : nameResolver.forInterview(scheduleId)
                    .map(name -> "The speaker's name is " + name + ".")
                    .orElse(null);
            // What was really spoken, asked at the same time as the transcription so it adds no wait.
            java.util.concurrent.CompletableFuture<java.util.Optional<String[]>> detected = detectLanguage
                    ? java.util.concurrent.CompletableFuture.supplyAsync(
                            () -> openAiStreamingService.detectLanguage(audioData, filename))
                    : java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
            TranscriptionResult result = openAiStreamingService.transcribe(audioData, filename, hint);
            // Counted before the chunk is reported done, so the answer is never submitted ahead of its count.
            try {
                java.util.Optional<String[]> language = detected.get(20, java.util.concurrent.TimeUnit.SECONDS);
                if (language.isPresent() && result.getText() != null && !result.getText().isBlank()) {
                    sharedState.increment(languageTotalKey(scheduleId), LAST_PUBLISHED_TTL);
                    if (looksForeign(language.get()[0], language.get()[1])) {
                        sharedState.increment(languageForeignKey(scheduleId), LAST_PUBLISHED_TTL);
                        sharedState.set(languageNameKey(scheduleId), language.get()[0], LAST_PUBLISHED_TTL);
                        log.info("Chunk {} for schedule {} was spoken in {}, not English", chunkIndex, scheduleId,
                                language.get()[0]);
                        // Told while they are still speaking, once per answer, so they can switch to English
                        // before they finish rather than find out afterwards.
                        if (sharedState.setIfAbsent(languageWarnedKey(scheduleId), "1", LAST_PUBLISHED_TTL)) {
                            messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/language-warning",
                                    Map.of("language", language.get()[0], "live", true));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("No language check for chunk {} of schedule {}", chunkIndex, scheduleId, e);
            }

            String comparable = result.getText() == null ? "" : TranscriptionSanitizer.comparable(result.getText());
            if (comparable.contains(" ") && comparable.equals(sharedState.get(lastPublishedKey(scheduleId)).orElse(null))) {
                log.info("Dropped chunk {} for schedule {}: the same words as the chunk before (\"{}\")",
                        chunkIndex, scheduleId, result.getText());
                publishDone(scheduleId, chunkIndex);
                return;
            }

            if (result.getText() != null && !result.getText().isBlank()) {
                sharedState.set(lastPublishedKey(scheduleId), comparable, LAST_PUBLISHED_TTL);
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
