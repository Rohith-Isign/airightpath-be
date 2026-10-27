package com.rightpath.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Keeps what the candidate said and drops what Whisper made up.
 *
 * <p>Whisper does not return an empty string for audio with no speech in it.
 * Handed a fan, a keyboard, a cough or a held breath, it returns fluent,
 * well-formed sentences — and because it was trained on a very large corpus of
 * subtitled video, the sentences it reaches for are the ones that end those
 * videos: "Thanks for watching", "Please subscribe", "Subtitles by the
 * Amara.org community". One interview had "Thank you for joining us" land in a
 * candidate's answer, having been said by nobody.</p>
 *
 * <p>Nothing downstream can tell that apart from a real answer. It goes into
 * the transcript, into the running summary, into the model's next question and
 * into the evaluation that decides whether somebody is hired. So it has to be
 * caught here, at the only point where the evidence for catching it exists.</p>
 *
 * <h2>What the evidence is</h2>
 *
 * <p>{@code verbose_json} returns per-segment statistics that Whisper's own
 * decoder uses for exactly this purpose, and that this codebase was parsing
 * and throwing away:</p>
 *
 * <ul>
 *   <li>{@code no_speech_prob} — the model's estimate that the window held no
 *       speech at all.</li>
 *   <li>{@code avg_logprob} — mean token confidence over the segment.</li>
 *   <li>{@code compression_ratio} — how repetitive the text is. A decoder that
 *       has fallen into a loop emits "yeah yeah yeah yeah" and this climbs.</li>
 * </ul>
 *
 * <h2>What it will not do</h2>
 *
 * <p>It errs towards keeping speech. A dropped sentence is unrecoverable — the
 * audio is gone and the candidate has moved on — while an invented one is at
 * least visible to a reviewer reading the transcript. So the main test needs
 * two independent signals to agree before anything is discarded, and the
 * phrase list only overrides that for strings no interview answer contains. A
 * candidate who ends an answer with "thank you" keeps it: that segment has
 * ordinary confidence behind it.</p>
 */
public final class TranscriptionSanitizer {

	private TranscriptionSanitizer() {
	}

	/**
	 * Above this, Whisper is saying the window probably held no speech.
	 *
	 * <p>The value its own reference decoder uses. Paired with
	 * {@link #AVG_LOGPROB_FLOOR} rather than used alone: on its own it fires on
	 * quiet but real speech, and a candidate who answers softly must not be
	 * transcribed as silence.</p>
	 */
	private static final double NO_SPEECH_CEILING = 0.6;

	/** Below this the decode went badly. Only acted on together with the above. */
	private static final double AVG_LOGPROB_FLOOR = -1.0;

	/**
	 * The relaxed ceiling, used only against {@link #WEAK_SIGN_OFFS}.
	 *
	 * <p>A whole segment consisting of nothing but "Thank you." is a different
	 * proposition from a whole segment of speech: a candidate thanking the
	 * interviewer does it in the same breath as the rest of their sentence, so
	 * the words land in a segment with other words. One sitting alone, with the
	 * model already half-saying there was no speech, is room tone.</p>
	 */
	private static final double WEAK_SIGN_OFF_NO_SPEECH_CEILING = 0.4;

	/**
	 * Above this the text is repetitive enough to be a decoder loop.
	 *
	 * <p>Acted on alone, because there is no reading of "the the the the the"
	 * worth keeping in a transcript somebody is scored against.</p>
	 */
	private static final double COMPRESSION_RATIO_CEILING = 2.4;

	/**
	 * Strings that are never an answer to an interview question.
	 *
	 * <p>Dropped on a whole-segment match whatever the confidence says, because
	 * these are Whisper reciting its training data: subtitle credits and
	 * sign-offs from the videos the corpus was built from. Compared after
	 * {@link #normalise}, so they carry no punctuation and no capitals —
	 * "Thanks for watching!" and "thanks for watching" are one entry.</p>
	 *
	 * <p>Deliberately not a list of everything Whisper has ever invented. A
	 * long list starts deleting things people say; these are the ones that
	 * cannot be.</p>
	 */
	private static final Set<String> SUBTITLE_ARTEFACTS = Set.of(
			"thanks for watching",
			"thank you for watching",
			"thanks for watching and see you next time",
			"please subscribe",
			"please subscribe to my channel",
			"subscribe to my channel",
			"like and subscribe",
			"see you in the next video",
			"see you next video",
			"subtitles by the amaraorg community",
			"subtitles by steamteamextra",
			"transcription by castingwordscom",
			"wwwmoojiorg");

	/**
	 * Generic sign-offs that are real speech about as often as they are not.
	 *
	 * <p>Dropped only when they are the entire segment and
	 * {@link #WEAK_SIGN_OFF_NO_SPEECH_CEILING} is also exceeded. The common
	 * case — a candidate saying "okay, so…" and carrying on — keeps its words,
	 * because those are not a segment on their own.</p>
	 */
	private static final Set<String> WEAK_SIGN_OFFS = Set.of(
			"thank you",
			"thank you very much",
			"thanks",
			"bye",
			"bye bye",
			"goodbye",
			"okay",
			"ok",
			"you",
			"oh",
			"hmm",
			"mm",
			"mhm");

	/** Punctuation, symbols, and the musical notes Whisper emits over music. */
	private static final Pattern NOISE_CHARACTERS = Pattern.compile("[\\p{Punct}\\u266a\\u266b\\u2669\\u00a0]");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/**
	 * What survived the filter.
	 *
	 * @param text      the kept words, joined; empty when nothing survived
	 * @param keptSpans start/end second pairs of the segments that were kept,
	 *                  so word timestamps belonging to discarded audio can be
	 *                  dropped alongside the text. Speaking rate and filler
	 *                  counts are derived from those words and are part of what
	 *                  a candidate is scored on; leaving in the words of a
	 *                  sentence nobody said would put invented speech back into
	 *                  the evaluation through the side door.
	 * @param rejected  one line per discarded segment, for the log
	 */
	public record Sanitized(String text, List<double[]> keptSpans, List<String> rejected) {
	}

	/**
	 * The candidate's words, with invented ones removed.
	 *
	 * @param verboseJson a Whisper {@code verbose_json} response
	 */
	public static Sanitized sanitize(JsonNode verboseJson) {
		if (verboseJson == null) {
			return new Sanitized("", List.of(), List.of());
		}

		JsonNode segments = verboseJson.path("segments");
		if (!segments.isArray() || segments.isEmpty()) {
			// Some models and gateways return text with no segments. With no
			// statistics to judge by there is nothing to filter on, and
			// discarding the lot would lose real answers — so only the
			// unmistakable artefacts come out, and every word is kept.
			return new Sanitized(cleanWithoutSegments(verboseJson.path("text").asText("")), List.of(), List.of());
		}

		List<String> kept = new ArrayList<>();
		List<double[]> spans = new ArrayList<>();
		List<String> rejected = new ArrayList<>();

		for (JsonNode segment : segments) {
			String reason = rejectionReason(segment);
			String trimmed = segment.path("text").asText("").trim();
			if (reason != null) {
				rejected.add("\"" + trimmed + "\" — " + reason);
				continue;
			}
			if (trimmed.isEmpty()) {
				continue;
			}
			kept.add(trimmed);
			spans.add(new double[] { segment.path("start").asDouble(0), segment.path("end").asDouble(0) });
		}

		return new Sanitized(String.join(" ", kept).trim(), spans, rejected);
	}

	/** The kept text alone, for callers with no use for the rest. */
	public static String clean(JsonNode verboseJson) {
		return sanitize(verboseJson).text();
	}

	/**
	 * Whether a word's timestamp falls inside audio that was kept.
	 *
	 * <p>Always true when there are no spans: that is the no-segments case,
	 * where nothing was filtered and so nothing should be.</p>
	 */
	public static boolean isWithin(List<double[]> keptSpans, double start, double end) {
		if (keptSpans.isEmpty()) {
			return true;
		}
		// Midpoint rather than either edge: Whisper's word and segment
		// boundaries are a few milliseconds apart, and a word sitting exactly
		// on a boundary should not be decided by rounding.
		double midpoint = (start + end) / 2;
		for (double[] span : keptSpans) {
			if (midpoint >= span[0] && midpoint <= span[1]) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Why this segment is not the candidate speaking, or null if it is.
	 *
	 * <p>The decision and its explanation are one method on purpose. Two would
	 * drift, and a log line that disagrees with what was actually dropped is
	 * worse than no log line at all.</p>
	 */
	public static String rejectionReason(JsonNode segment) {
		String normalised = normalise(segment.path("text").asText(""));

		if (normalised.isEmpty()) {
			// Punctuation or a musical note on its own. Whisper emits these
			// over music and over silence; neither is something a person said.
			return "no words";
		}

		if (SUBTITLE_ARTEFACTS.contains(normalised)) {
			return "subtitle artefact";
		}

		double compressionRatio = segment.path("compression_ratio").asDouble(0);
		if (compressionRatio > COMPRESSION_RATIO_CEILING) {
			return "repetition loop (compression_ratio " + compressionRatio + ")";
		}

		JsonNode noSpeechNode = segment.path("no_speech_prob");
		JsonNode avgLogprobNode = segment.path("avg_logprob");
		if (noSpeechNode.isMissingNode() || avgLogprobNode.isMissingNode()) {
			// No statistics, no grounds. Keeping it is the safer failure.
			return null;
		}
		double noSpeech = noSpeechNode.asDouble(0);
		double avgLogprob = avgLogprobNode.asDouble(0);

		if (noSpeech > NO_SPEECH_CEILING && avgLogprob < AVG_LOGPROB_FLOOR) {
			return "no speech detected (no_speech_prob " + noSpeech + ", avg_logprob " + avgLogprob + ")";
		}

		if (WEAK_SIGN_OFFS.contains(normalised) && noSpeech > WEAK_SIGN_OFF_NO_SPEECH_CEILING) {
			return "stock phrase over near-silence (no_speech_prob " + noSpeech + ")";
		}

		return null;
	}

	private static String cleanWithoutSegments(String text) {
		String normalised = normalise(text);
		if (normalised.isEmpty() || SUBTITLE_ARTEFACTS.contains(normalised)) {
			return "";
		}
		return text.trim();
	}

	/** Lowercased, stripped of punctuation and collapsed, for comparison only. */
	private static String normalise(String text) {
		if (text == null) {
			return "";
		}
		String stripped = NOISE_CHARACTERS.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
		return WHITESPACE.matcher(stripped).replaceAll(" ").trim();
	}
}
