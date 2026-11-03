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
	 * A looser pairing than {@link #NO_SPEECH_CEILING} with {@link #AVG_LOGPROB_FLOOR}.
	 *
	 * <p>The strict pair missed what a silent or noisy microphone actually produces: confident-looking
	 * invented sentences that sit just short of both limits. Both signals still have to agree.</p>
	 */
	private static final double LIKELY_NO_SPEECH = 0.45;
	private static final double LIKELY_POOR_DECODE = -0.8;

	/**
	 * A decode this poor is not speech worth keeping whatever else is said about it.
	 *
	 * <p>Whisper's own reference decoder treats -1 as the line below which it retries; this is well past it, so
	 * accented or quiet speech, which sits around -0.5 to -1.0, is not caught.</p>
	 */
	private static final double GARBAGE_DECODE = -1.5;

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
			"wwwmoojiorg",
			// More of the same family, seen in a real interview's transcript over a silent microphone. None of
			// these is something a candidate says in answer to an interview question.
			"i love you",
			"i love you all",
			"i love you too",
			"please like share and subscribe",
			"like share and subscribe",
			"like share comment and subscribe",
			"please like share and comment on this video",
			"if you like this video please subscribe",
			"please subscribe to our channel for more such videos",
			"please subscribe to my channel and i will see you in the next video",
			"sleep well",
			"shhh",
			"peace",
			"see you next time",
			"see you soon",
			// Seen appended to, or standing in for, an answer over a quiet microphone. Whole-segment matches only.
			"thank you for joining us",
			"thanks for joining us",
			"thank you for joining",
			"thanks for joining",
			"thank you for watching this video",
			"thank you for listening",
			"thanks for listening",
			"i will see you in the next video",
			"see you in the next one",
			"bye for now",
			"music",
			"applause",
			"laughter",
			"silence",
			"blank audio");

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
			"thank you very much", "thank you so much", "thank you for your time",
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

		for (int i = 0; i < segments.size(); i++) {
			JsonNode segment = segments.get(i);
			String reason = rejectionReason(segment);
			// The first and last segments of a recording are where Whisper tacks on a stray sign-off after the
			// speech has stopped, so they are held to a stricter standard than a segment in the middle.
			if (reason == null && (i == 0 || i == segments.size() - 1)) {
				reason = edgeSignOffReason(segment);
			}
			String trimmed = segment.path("text").asText("").trim();
			if (reason != null) {
				rejected.add("\"" + trimmed + "\" — " + reason);
				continue;
			}
			if (trimmed.isEmpty()) {
				continue;
			}
			// A stock sign-off glued onto the end of real speech ("... Thank you for watching and please subscribe to
			// my channel.") is taken out; the words before it stay.
			trimmed = stripArtefactSentences(trimmed);
			if (trimmed.isEmpty()) {
				continue;
			}
			kept.add(trimmed);
			spans.add(new double[] { segment.path("start").asDouble(0), segment.path("end").asDouble(0) });
		}

		String joined = String.join(" ", kept).trim();
		// A loop can be spread across several segments, each of which looks ordinary on its own, so the joined
		// text is judged as well. Nothing in it is speech, so no word timestamp is kept either: the one span that
		// no real word falls inside makes isWithin() reject them all, where an empty list would keep them all.
		if (isRepetitionLoop(joined)) {
			rejected.add("\"" + joined + "\" — repetition loop across the recording");
			return new Sanitized("", List.of(new double[] { -1, -1 }), rejected);
		}
		return new Sanitized(joined, spans, rejected);
	}

	/** The comparison form used to spot the same text arriving twice in a row. */
	public static String comparable(String text) {
		return normalise(text);
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

		if (isRepetitionLoop(segment.path("text").asText(""))) {
			return "repetition loop (the same phrase over and over)";
		}

		if (isMostlyNonLatin(segment.path("text").asText(""))) {
			return "not English (the interview is in English, and this is another script)";
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

		if (avgLogprob < GARBAGE_DECODE) {
			return "decode too poor to be speech (avg_logprob " + avgLogprob + ")";
		}

		if (noSpeech > LIKELY_NO_SPEECH && avgLogprob < LIKELY_POOR_DECODE) {
			return "probably no speech (no_speech_prob " + noSpeech + ", avg_logprob " + avgLogprob + ")";
		}

		if (noSpeech > NO_SPEECH_CEILING && avgLogprob < AVG_LOGPROB_FLOOR) {
			return "no speech detected (no_speech_prob " + noSpeech + ", avg_logprob " + avgLogprob + ")";
		}

		if (WEAK_SIGN_OFFS.contains(normalised) && noSpeech > WEAK_SIGN_OFF_NO_SPEECH_CEILING) {
			return "stock phrase over near-silence (no_speech_prob " + noSpeech + ")";
		}

		return null;
	}

	/**
	 * Above this, a sign-off at the edge of a recording has some doubt about it.
	 *
	 * <p>A candidate really saying "thank you" at the end of an answer is decoded confidently (avg_logprob close
	 * to zero, no_speech_prob near zero). The invented one, said over the tail of room tone, is not.</p>
	 */
	private static final double EDGE_SIGN_OFF_NO_SPEECH = 0.2;
	private static final double EDGE_SIGN_OFF_POOR_DECODE = -0.5;

	/**
	 * Why a stock sign-off at the start or end of the recording is not the candidate, or null if it may be.
	 *
	 * <p>Only a whole segment of a stock phrase, and only with some doubt in the statistics. Words that share a
	 * segment with real speech are never touched, and a segment with no statistics is kept.</p>
	 */
	private static String edgeSignOffReason(JsonNode segment) {
		String normalised = normalise(segment.path("text").asText(""));
		if (!WEAK_SIGN_OFFS.contains(normalised)) {
			return null;
		}
		JsonNode noSpeechNode = segment.path("no_speech_prob");
		JsonNode avgLogprobNode = segment.path("avg_logprob");
		if (noSpeechNode.isMissingNode() || avgLogprobNode.isMissingNode()) {
			return null;
		}
		double noSpeech = noSpeechNode.asDouble(0);
		double avgLogprob = avgLogprobNode.asDouble(0);
		if (noSpeech > EDGE_SIGN_OFF_NO_SPEECH || avgLogprob < EDGE_SIGN_OFF_POOR_DECODE) {
			return "stray sign-off at the edge of the recording (no_speech_prob " + noSpeech
					+ ", avg_logprob " + avgLogprob + ")";
		}
		return null;
	}

	private static String cleanWithoutSegments(String text) {
		String normalised = normalise(text);
		if (normalised.isEmpty() || SUBTITLE_ARTEFACTS.contains(normalised) || isRepetitionLoop(text)
				|| isMostlyNonLatin(text)) {
			return "";
		}
		return text.trim();
	}

	/** Phrases no candidate says in an interview; a sentence holding one is a video sign-off Whisper made up. */
	private static final List<String> SIGN_OFF_MARKERS = List.of("thank you for watching", "thanks for watching",
			"subscribe to my channel", "please subscribe", "like and subscribe");

	/** Sentences that are only a subtitle sign-off, wherever they sit in a segment. */
	private static String stripArtefactSentences(String text) {
		String[] sentences = text.split("(?<=[.!?])\s+");
		if (sentences.length < 2) {
			return text;
		}
		StringBuilder out = new StringBuilder();
		for (String sentence : sentences) {
			String normalised = normalise(sentence);
			if (SUBTITLE_ARTEFACTS.contains(normalised) || SIGN_OFF_MARKERS.stream().anyMatch(normalised::contains)) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(sentence);
		}
		return out.toString().trim();
	}

	// ── Not English ─────────────────────────────────────────────────────

	/** Share of letters outside the Latin alphabet above which a segment is not English. */
	private static final double NON_LATIN_SHARE = 0.5;

	/**
	 * Whether most of the letters are in another script: Cyrillic, Arabic, Devanagari, CJK and so on.
	 *
	 * <p>The language is already fixed to English in the request, but on room noise Whisper still emits text in
	 * other scripts, which is never a candidate answering an English interview. Latin-alphabet words in another
	 * language cannot be told apart this way and are left alone; a code answer or an unusual name inside a
	 * sentence in English is a few letters, nowhere near half.</p>
	 */
	static boolean isMostlyNonLatin(String text) {
		if (text == null) {
			return false;
		}
		int letters = 0;
		int nonLatin = 0;
		for (int i = 0; i < text.length();) {
			int codePoint = text.codePointAt(i);
			i += Character.charCount(codePoint);
			if (!Character.isLetter(codePoint)) {
				continue;
			}
			letters++;
			if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN) {
				nonLatin++;
			}
		}
		return letters > 0 && nonLatin >= letters * NON_LATIN_SHARE;
	}

	// ── Repetition loops ────────────────────────────────────────────────

	/** Fewer words than this cannot be told from someone emphasising a point. */
	private static final int LOOP_MIN_WORDS = 8;

	/** Longest phrase looked for. The loop in the field was nine words. */
	private static final int LOOP_MAX_PHRASE = 12;

	/** Share of the words that must sit inside repeats before the text is called a loop. */
	private static final double LOOP_COVERAGE = 0.5;

	/**
	 * Whether the text is one phrase repeated over and over, which Whisper does on low-level noise.
	 *
	 * <p>It is the other thing it makes up besides stock sign-offs: left to run, it loops a sentence ("I'm going
	 * to go to the gym and then...") for the length of the recording. The per-segment statistics do not catch it
	 * when the loop is spread over several segments, so this reads the words themselves. Nobody repeats a phrase of
	 * two to twelve words three or more times in a row for most of an answer.</p>
	 */
	static boolean isRepetitionLoop(String text) {
		String normalised = normalise(text);
		if (normalised.isEmpty()) {
			return false;
		}
		String[] words = normalised.split(" ");
		if (words.length < LOOP_MIN_WORDS) {
			return false;
		}

		boolean[] covered = new boolean[words.length];
		for (int length = 1; length <= LOOP_MAX_PHRASE; length++) {
			// A single word repeated is easier to say on purpose ("no, no, no"), so it takes more of them.
			int minRepeats = length == 1 ? 6 : 3;
			int i = 0;
			while (i + length <= words.length) {
				int repeats = 1;
				while (matchesAt(words, i, i + repeats * length, length)) {
					repeats++;
				}
				if (repeats >= minRepeats) {
					for (int w = i; w < i + repeats * length; w++) {
						covered[w] = true;
					}
					i += repeats * length;
				} else {
					i++;
				}
			}
		}
		int coveredCount = 0;
		for (boolean c : covered) {
			if (c) {
				coveredCount++;
			}
		}
		if (coveredCount >= words.length * LOOP_COVERAGE) {
			return true;
		}
		return false;
	}

	/** Whether the {@code length} words at {@code from} are the same as the ones at {@code start}. */
	private static boolean matchesAt(String[] words, int start, int from, int length) {
		if (from + length > words.length) {
			return false;
		}
		for (int k = 0; k < length; k++) {
			if (!words[start + k].equals(words[from + k])) {
				return false;
			}
		}
		return true;
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
