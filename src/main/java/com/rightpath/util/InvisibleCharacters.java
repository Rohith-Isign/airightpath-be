package com.rightpath.util;

import java.util.regex.Pattern;

/**
 * Invisible characters: text that takes up a character but shows nothing.
 *
 * <p>A job description of a single zero-width space looked empty, counted as one character, and passed every
 * "required" check. The page now removes these as they are typed; this removes them again from everything the server
 * receives as JSON (see {@link com.rightpath.config.InvisibleCharacterConfig}), so a request that did not come
 * through the page cannot carry them either. The same rules as the page's {@code stripInvisible}.</p>
 */
public final class InvisibleCharacters {

    /** Format characters (zero-width spaces and joiners, direction marks, BOM, soft hyphen) and blank-looking letters. */
    private static final Pattern INVISIBLE =
            Pattern.compile("[\\p{Cf}\\u034F\\u115F\\u1160\\u17B4\\u17B5\\u2800\\u3164\\uFFA0]");

    /** Unusual spaces: shown as a space, so made an ordinary one. */
    private static final Pattern ODD_SPACES = Pattern.compile("[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]");

    /** Line and paragraph separators: made an ordinary line break. */
    private static final Pattern ODD_BREAKS = Pattern.compile("[\\u2028\\u2029]");

    private InvisibleCharacters() {
    }

    /**
     * The text without invisible characters. The zero-width joiners (U+200C, U+200D) are kept only between two
     * non-ASCII characters, where they do real work (joined emoji, Indian-script conjuncts).
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\u200C' || ch == '\u200D') {
                boolean joins = i > 0 && i < text.length() - 1 && text.charAt(i - 1) > 0x7F
                        && text.charAt(i + 1) > 0x7F;
                if (joins) {
                    kept.append(ch);
                }
                continue;
            }
            kept.append(ch);
        }
        String cleaned = INVISIBLE.matcher(kept).replaceAll(m -> {
            String c = m.group();
            return c.equals("\u200C") || c.equals("\u200D") ? c : "";
        });
        cleaned = ODD_SPACES.matcher(cleaned).replaceAll(" ");
        return ODD_BREAKS.matcher(cleaned).replaceAll("\n");
    }

    /** Whether a value is empty once invisible characters and spaces are taken out. */
    public static boolean isBlank(String text) {
        return text == null || strip(text).isBlank();
    }
}
