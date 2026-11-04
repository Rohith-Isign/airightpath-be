package com.rightpath.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Removes a greeting the model added to the opening question.
 *
 * <p>The interview opens with a fixed welcome the server writes ("Hello Praveen, and welcome. I'm Sarah, ...").
 * The model is told not to greet, but on some openings it does anyway, so the candidate heard the welcome twice:
 * "Hello ... Let's begin. Hi Praveen, welcome to the interview. I'm Sarah, ..." This takes any such greeting off
 * the front of what the model wrote, leaving the question.</p>
 *
 * <p>Only leading sentences are dropped, and never the last one: a reply that is all greeting keeps its final
 * sentence rather than becoming empty.</p>
 */
public final class GreetingStripper {

    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+");

    /** Sentences that are the model welcoming or introducing itself, not asking anything. */
    private static final Pattern GREETING = Pattern.compile(
            "^\\W*(hi|hello|hey|welcome|greetings|good\\s+(morning|afternoon|evening)|"
                    + "thanks?\\s+(you\\s+)?for\\s+(joining|coming)|"
                    + "i['’]?m\\s+\\w+(,|\\s+and)|i\\s+am\\s+\\w+(,|\\s+and)|my\\s+name\\s+is|"
                    + "i['’]?ll\\s+be\\s+(asking|taking|conducting)|i\\s+will\\s+be\\s+(asking|taking|conducting)|"
                    + "let['’]?s\\s+(begin|get\\s+started|start))(?![A-Za-z]).*",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private GreetingStripper() {
    }

    /** The text without its leading greeting sentences; unchanged when it has none. */
    public static String strip(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] parts = SENTENCE_BREAK.split(text.trim());
        if (parts.length < 2) {
            return text;
        }
        List<String> kept = new ArrayList<>(List.of(parts));
        // Never drop the last sentence: that is the question, or the nearest thing to one.
        while (kept.size() > 1 && GREETING.matcher(kept.get(0)).matches() && !kept.get(0).contains("?")) {
            kept.remove(0);
        }
        return String.join(" ", kept);
    }
}
