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

    /**
     * Which answer a language check belongs to. Moved on each time an answer is processed, so a check that finishes
     * after its answer was sent is not counted against the next one.
     */
    public static String languageGenerationKey(Long scheduleId) {
        return "lang-gen:" + scheduleId;
    }

    /**
     * Language checks run here, never on the transcription threads and never on the JVM's shared pool. They used to
     * hold each chunk's transcript back until they finished, on a pool that can be a single thread on a small server;
     * under load transcripts arrived late, the page stopped waiting and submitted an answer with words missing or
     * none at all, and the interviewer said it had not caught anything.
     */
    private static final java.util.concurrent.ExecutorService LANGUAGE_CHECKS =
            java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "language-check");
                thread.setDaemon(true);
                return thread;
            });

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

    /**
     * Counts one chunk's language towards the answer it belongs to, and warns the candidate the first time another
     * language is heard. Ignored when the answer has already been sent (the generation has moved on).
     */
    private void countLanguage(Long scheduleId, int chunkIndex, String generation,
            java.util.Optional<String[]> language) {
        if (language.isEmpty()
                || !generation.equals(sharedState.get(languageGenerationKey(scheduleId)).orElse("0"))) {
            return;
        }
        sharedState.increment(languageTotalKey(scheduleId), LAST_PUBLISHED_TTL);
        if (!looksForeign(language.get()[0], language.get()[1])) {
            return;
        }
        sharedState.increment(languageForeignKey(scheduleId), LAST_PUBLISHED_TTL);
        sharedState.set(languageNameKey(scheduleId), language.get()[0], LAST_PUBLISHED_TTL);
        log.info("Chunk {} for schedule {} was spoken in {}, not English", chunkIndex, scheduleId, language.get()[0]);
        // Told while they are still speaking, once per answer, so they can switch to English before they finish.
        if (sharedState.setIfAbsent(languageWarnedKey(scheduleId), "1", LAST_PUBLISHED_TTL)) {
            messagingTemplate.convertAndSend("/topic/interview/" + scheduleId + "/language-warning",
                    Map.of("language", language.get()[0], "live", true));
        }
    }

    @Async("transcriptionExecutor")
    public void transcribeAsync(Long scheduleId, byte[] audioData, int chunkIndex) {
        log.info("Transcribing chunk {} for schedule {} ({} bytes)", chunkIndex, scheduleId, audioData.length);

        try {
            String filename = String.format("chunk_%d.webm", chunkIndex);
            String hint = nameResolver == null ? null : nameResolver.forInterview(scheduleId)
                    .map(name -> "The speaker's name is " + name + ".")
                    .orElse(null);
            // What was really spoken: checked alongside, on its own threads, and never waited for. The transcript
            // is published as soon as it is ready; the check counts towards the answer when it finishes.
            if (detectLanguage) {
                String generation = sharedState.get(languageGenerationKey(scheduleId)).orElse("0");
                java.util.concurrent.CompletableFuture
                        .supplyAsync(() -> openAiStreamingService.detectLanguage(audioData, filename), LANGUAGE_CHECKS)
                        .thenAccept(language -> countLanguage(scheduleId, chunkIndex, generation, language))
                        .exceptionally(e -> {
                            log.debug("No language check for chunk {} of schedule {}", chunkIndex, scheduleId, e);
                            return null;
                        });
            }
            TranscriptionResult result = openAiStreamingService.transcribe(audioData, filename, hint);
            // The speech model sometimes says its hint back on a short or quiet chunk ("The speaker's name is
            // Sai."), which is not something the candidate said.
            if (hint != null && result.getText() != null && result.getText().contains("speaker's name is")) {
                result = new TranscriptionResult(
                        result.getText().replaceAll("(?i)the speaker's name is [^.]*\\.?", "").trim(),
                        result.getDuration(), result.getWords());
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
