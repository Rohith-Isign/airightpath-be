package com.rightpath.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.enums.TurnKind;
import com.rightpath.service.InterviewConductPolicy;

/**
 * Reads the control tags off a model reply and hands back the speech underneath.
 *
 * <p>The interviewer talks to the server in the same message it talks to the
 * candidate in: a short bracketed preamble the candidate never hears, then the
 * words they do. {@code [CODING]} already worked this way — the browser opens
 * the code editor when it sees one — and the rest follow the same shape rather
 * than inventing a second channel, because there is only one reply to carry
 * them in and a second model call per turn would be heard as a pause.</p>
 *
 * <pre>
 *   [SCORE:7] [TOPIC:Data Structures] [FOLLOWUP] You mentioned hash maps — what
 *   happens when two keys collide?
 * </pre>
 *
 * <p>Every tag is optional. A reply with none of them is a new question on an
 * unnamed topic with no rating, which is exactly what the model produced before
 * any of this existed — so a model that ignores the protocol degrades to the old
 * behaviour rather than breaking the interview.</p>
 */
@Component
public class InterviewReplyParser {

    /** {@code [SCORE:7]} — the model's rating of the answer it just heard. */
    private static final Pattern SCORE = Pattern.compile(
            "\\[\\s*SCORE\\s*:\\s*(10|\\d)\\s*(?:/\\s*10\\s*)?\\]", Pattern.CASE_INSENSITIVE);

    /** {@code [TOPIC:Data Structures]} — which evaluation category this question is for. */
    private static final Pattern TOPIC = Pattern.compile(
            "\\[\\s*TOPIC\\s*:\\s*([^\\]]{1,60})\\]", Pattern.CASE_INSENSITIVE);

    /** {@code [FOLLOWUP]} — pressing on the question already asked. */
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "\\[\\s*FOLLOW[ _-]?UP\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** {@code [REPHRASE]} — the same concept, asked another way. */
    private static final Pattern REPHRASE = Pattern.compile(
            "\\[\\s*REPHRASE\\s*\\]", Pattern.CASE_INSENSITIVE);

    /** The tag the browser reads to open the code editor. Kept, and kept in front. */
    private static final Pattern CODING_TAG = Pattern.compile(
            "\\[\\s*CODING\\s*\\]", Pattern.CASE_INSENSITIVE);

    private final InterviewConductPolicy conductPolicy;

    public InterviewReplyParser(InterviewConductPolicy conductPolicy) {
        this.conductPolicy = conductPolicy;
    }

    /**
     * Splits a raw reply into its control data and its speech.
     *
     * @param reply exactly what the model returned
     */
    public ParsedInterviewReply parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return new ParsedInterviewReply("", null, TurnKind.NEW_QUESTION, null, false);
        }

        boolean closing = conductPolicy.isClosing(reply);

        Integer score = firstInt(SCORE, reply);
        String topic = firstGroup(TOPIC, reply);

        // A reply tagged both ways is the model hedging. Rephrase is the stronger
        // signal — it says the question itself did not land — so it wins, and
        // either way the turn stays on the same subject.
        TurnKind kind;
        if (REPHRASE.matcher(reply).find()) {
            kind = TurnKind.REPHRASE;
        } else if (FOLLOW_UP.matcher(reply).find()) {
            kind = TurnKind.FOLLOW_UP;
        } else {
            kind = TurnKind.NEW_QUESTION;
        }

        String spoken = conductPolicy.stripCompletionMarker(reply);
        spoken = SCORE.matcher(spoken).replaceAll("");
        spoken = TOPIC.matcher(spoken).replaceAll("");
        spoken = FOLLOW_UP.matcher(spoken).replaceAll("");
        spoken = REPHRASE.matcher(spoken).replaceAll("");
        spoken = promoteCodingTag(spoken);

        return new ParsedInterviewReply(spoken, score, kind, topic, closing);
    }

    /**
     * Puts {@code [CODING]} back at the front, if it is there at all.
     *
     * <p>Removing the tags in front of it can leave it mid-sentence, and the
     * browser only opens the editor for a tag that leads the message — a
     * candidate would be asked to write code with nowhere to write it. Collapses
     * the whitespace the removals left behind at the same time, so the candidate
     * is not read a reply that starts with a blank line.</p>
     */
    private String promoteCodingTag(String text) {
        Matcher coding = CODING_TAG.matcher(text);
        if (!coding.find()) {
            return tidy(text);
        }
        String withoutTag = text.substring(0, coding.start()) + text.substring(coding.end());
        return (InterviewConductPolicy.CODING_TAG + " " + tidy(withoutTag)).trim();
    }

    /** Collapses the runs of blank space that tag removal leaves behind. */
    private String tidy(String text) {
        return text.replaceAll("[ \\t]{2,}", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private Integer firstInt(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int value = Integer.parseInt(matcher.group(1).trim());
            return value >= 0 && value <= 10 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
