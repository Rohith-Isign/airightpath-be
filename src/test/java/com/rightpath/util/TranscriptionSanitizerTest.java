package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Only the candidate's words reach the transcript.
 *
 * <p>Every "drop" case here is a shape Whisper actually produces on audio with
 * no speech in it, and every "keep" case is the thing a filter like this gets
 * wrong: quiet speech, an unfamiliar accent, technical vocabulary, and a
 * candidate who politely says thank you. Getting the second group wrong is the
 * more serious failure — a dropped sentence cannot be recovered, while an
 * invented one is at least visible to a reviewer.</p>
 */
class TranscriptionSanitizerTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A segment with the statistics of ordinary, confident speech. */
	private static String speech(String text) {
		return segment(text, 0.01, -0.25, 1.4, 0, 5);
	}

	private static String segment(String text, double noSpeechProb, double avgLogprob, double compressionRatio,
			double start, double end) {
		return """
				{"text": "%s", "no_speech_prob": %s, "avg_logprob": %s,
				 "compression_ratio": %s, "start": %s, "end": %s}
				""".formatted(text, noSpeechProb, avgLogprob, compressionRatio, start, end);
	}

	private static JsonNode response(String... segments) {
		try {
			return MAPPER.readTree("""
					{"text": "ignored", "duration": 5.0, "segments": [%s]}
					""".formatted(String.join(",", segments)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// ── What must be dropped ──────────────────────────────────────────

	/**
	 * The exact failure that put "Thank you for joining us" into a real
	 * candidate's answer: Whisper's own numbers said there was no speech, and
	 * it produced a fluent sentence from the silence anyway.
	 */
	@Test
	void aSentenceInventedOverSilenceIsDropped() {
		JsonNode response = response(segment("Thank you for joining us.", 0.91, -1.6, 1.1, 0, 3));

		assertEquals("", TranscriptionSanitizer.clean(response));
	}

	@Test
	void subtitleCreditsAreDroppedHoweverConfidentTheModelIs() {
		// Confident statistics throughout — this is Whisper reciting its
		// training data, not failing to decode.
		assertEquals("", TranscriptionSanitizer.clean(response(speech("Thanks for watching!"))));
		assertEquals("", TranscriptionSanitizer.clean(response(speech("Please subscribe to my channel"))));
		assertEquals("",
				TranscriptionSanitizer.clean(response(speech("Subtitles by the Amara.org community"))));
	}

	@Test
	void aRepetitionLoopIsDropped() {
		JsonNode response = response(
				segment("yeah yeah yeah yeah yeah yeah yeah yeah", 0.05, -0.4, 3.9, 0, 4));

		assertEquals("", TranscriptionSanitizer.clean(response));
	}

	@Test
	void punctuationAndMusicalNotesAreNotWords() {
		assertEquals("", TranscriptionSanitizer.clean(response(speech("..."))));
		assertEquals("", TranscriptionSanitizer.clean(response(speech("\\u266a"))));
	}

	/**
	 * A stock phrase alone in its own segment, with the model already leaning
	 * towards "no speech". A real thank-you arrives inside a sentence.
	 */
	@Test
	void aStockPhraseOverNearSilenceIsDropped() {
		JsonNode response = response(segment("Thank you.", 0.55, -0.5, 1.0, 0, 2));

		assertEquals("", TranscriptionSanitizer.clean(response));
	}

	// ── What must be kept ─────────────────────────────────────────────

	@Test
	void realSpeechIsKeptWhole() {
		JsonNode response = response(
				speech("I would use a hash map for the lookup."),
				segment("That brings it down to linear time.", 0.02, -0.3, 1.5, 5, 9));

		assertEquals("I would use a hash map for the lookup. That brings it down to linear time.",
				TranscriptionSanitizer.clean(response));
	}

	/**
	 * A low {@code avg_logprob} on its own is an accent, a technical term or a
	 * poor microphone — exactly the speech most worth keeping. It must take
	 * {@code no_speech_prob} agreeing before anything is discarded.
	 */
	@Test
	void aPoorlyDecodedButRealAnswerIsKept() {
		JsonNode response = response(
				segment("I used Kubernetes with an Istio sidecar.", 0.04, -1.4, 1.6, 0, 4));

		assertEquals("I used Kubernetes with an Istio sidecar.", TranscriptionSanitizer.clean(response));
	}

	/** And the mirror image: quiet speech the model decoded cleanly. */
	@Test
	void quietSpeechTheModelReadWellIsKept() {
		JsonNode response = response(segment("Yes, that is right.", 0.78, -0.3, 1.2, 0, 2));

		assertEquals("Yes, that is right.", TranscriptionSanitizer.clean(response));
	}

	@Test
	void aCandidateThankingTheInterviewerKeepsTheirWords() {
		JsonNode response = response(speech("Thank you, that was a good question."));

		assertEquals("Thank you, that was a good question.", TranscriptionSanitizer.clean(response));
	}

	@Test
	void realSpeechSurvivesAlongsideAnInventedSegment() {
		JsonNode response = response(
				speech("The index makes the query faster."),
				segment("Thanks for watching!", 0.95, -1.8, 1.0, 5, 7));

		assertEquals("The index makes the query faster.", TranscriptionSanitizer.clean(response));
	}

	// ── Word timestamps ───────────────────────────────────────────────

	/**
	 * Words belonging to a discarded segment have to go with it. Speaking rate
	 * and filler counts are derived from them and feed the evaluation, so
	 * leaving them in would put the invented sentence back in through the
	 * side door.
	 */
	@Test
	void wordsFromADroppedSegmentAreNotKept() {
		JsonNode response = response(
				speech("Real answer here."),
				segment("Thanks for watching!", 0.95, -1.9, 1.0, 5, 7));

		TranscriptionSanitizer.Sanitized sanitized = TranscriptionSanitizer.sanitize(response);

		assertEquals(1, sanitized.keptSpans().size());
		assertTrue(TranscriptionSanitizer.isWithin(sanitized.keptSpans(), 1.0, 1.4));
		assertFalse(TranscriptionSanitizer.isWithin(sanitized.keptSpans(), 5.5, 6.0));
	}

	/** No segments means no grounds to filter, so no words are filtered either. */
	@Test
	void everyWordIsKeptWhenThereAreNoSpansToJudgeBy() {
		assertTrue(TranscriptionSanitizer.isWithin(List.of(), 99.0, 100.0));
	}

	// ── Degenerate input ──────────────────────────────────────────────

	/**
	 * Some models and gateways return text with no segments. With no
	 * statistics there is nothing to judge by, so only the unmistakable
	 * artefacts come out and everything else is kept.
	 */
	@Test
	void aResponseWithoutSegmentsKeepsItsTextButStillLosesArtefacts() throws Exception {
		JsonNode plain = MAPPER.readTree("{\"text\": \"I would cache the result.\"}");
		assertEquals("I would cache the result.", TranscriptionSanitizer.clean(plain));

		JsonNode artefact = MAPPER.readTree("{\"text\": \"Thanks for watching!\"}");
		assertEquals("", TranscriptionSanitizer.clean(artefact));
	}

	@Test
	void nullAndEmptyInputAreNotAnError() throws Exception {
		assertEquals("", TranscriptionSanitizer.clean(null));
		assertEquals("", TranscriptionSanitizer.clean(MAPPER.readTree("{}")));
		assertEquals("", TranscriptionSanitizer.clean(MAPPER.readTree("{\"text\": \"\", \"segments\": []}")));
	}

	/**
	 * An empty {@code segments} array is the no-segments case, not "everything
	 * was rejected" — so the top-level text stands. Reading it the other way
	 * would silently blank every transcription from a gateway that omits
	 * segments.
	 */
	@Test
	void anEmptySegmentsArrayFallsBackToTheTopLevelText() throws Exception {
		JsonNode response = MAPPER.readTree(
				"{\"text\": \"I would cache the result.\", \"segments\": []}");

		assertEquals("I would cache the result.", TranscriptionSanitizer.clean(response));
	}

	@Test
	void theReasonForEveryDropIsRecorded() {
		JsonNode response = response(
				speech("Real answer."),
				segment("Thanks for watching!", 0.95, -1.9, 1.0, 5, 7));

		TranscriptionSanitizer.Sanitized sanitized = TranscriptionSanitizer.sanitize(response);

		assertEquals(1, sanitized.rejected().size());
		assertTrue(sanitized.rejected().get(0).contains("subtitle artefact"),
				"expected a stated reason, got: " + sanitized.rejected().get(0));
	}
}
