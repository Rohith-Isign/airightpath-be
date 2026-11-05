package com.rightpath.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores resume text against a job's key skills.
 *
 * <p>Resume text comes out of PDF and Word parsing with line breaks, hyphens and odd spacing in the middle of
 * phrases ("Spring\nBoot", "spring-boot", "SpringBoot"), so a plain substring test against the raw text scores
 * a good resume 0%. Matching here is done on a squashed form with the spacing and punctuation removed, so those
 * all agree. Very short variations ("js", "ng", "ai") are matched only as whole words, otherwise "ts" would be
 * found inside "projects" and "py" inside "happy".</p>
 */
public final class SkillMatcher {

    /** Variations this short or shorter must match a whole word, not part of one. */
    private static final int WHOLE_WORD_ONLY_UP_TO = 3;

    private static final Pattern SKILL_SEPARATORS = Pattern.compile("[,;\\n\\r|]+");
    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9+#]+");

    private SkillMatcher() {
    }

    /** The job's key skills as lowercase, trimmed, de-duplicated tokens; blanks dropped. */
    public static List<String> parseSkills(String keySkills) {
        if (keySkills == null) {
            return List.of();
        }
        Set<String> skills = new LinkedHashSet<>();
        for (String raw : SKILL_SEPARATORS.split(keySkills)) {
            String skill = raw.trim().toLowerCase(Locale.ROOT);
            if (!skill.isEmpty()) {
                skills.add(skill);
            }
        }
        return List.copyOf(skills);
    }

    /**
     * Percentage (0 to 100) of the skills the resume covers, counting each skill once.
     *
     * @param synonyms skill to the other words that mean it; a skill with no entry matches only itself
     */
    public static double matchPercent(String resumeText, List<String> skills, Map<String, List<String>> synonyms) {
        if (resumeText == null || resumeText.isBlank() || skills.isEmpty()) {
            return 0.0;
        }
        String words = " " + NOT_ALNUM.matcher(resumeText.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
        String squashed = words.replace(" ", "");

        long matched = skills.stream().filter(skill -> covers(words, squashed, skill, synonyms)).count();
        return matched * 100.0 / skills.size();
    }

    /** The skills the resume covers, in the order the job lists them. */
    public static List<String> coveredSkills(String resumeText, List<String> skills,
                                             Map<String, List<String>> synonyms) {
        if (resumeText == null || resumeText.isBlank() || skills.isEmpty()) {
            return List.of();
        }
        String words = " " + NOT_ALNUM.matcher(resumeText.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
        String squashed = words.replace(" ", "");
        return skills.stream().filter(skill -> covers(words, squashed, skill, synonyms)).toList();
    }

    private static boolean covers(String words, String squashed, String skill, Map<String, List<String>> synonyms) {
        List<String> variations = new ArrayList<>(synonyms.getOrDefault(skill, List.of()));
        variations.add(skill);
        for (String variation : variations) {
            String spaced = NOT_ALNUM.matcher(variation.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
            String compact = spaced.replace(" ", "");
            if (compact.isEmpty()) {
                continue;
            }
            boolean found = compact.length() <= WHOLE_WORD_ONLY_UP_TO
                    ? words.contains(" " + spaced + " ")
                    : squashed.contains(compact);
            if (found) {
                return true;
            }
        }
        return false;
    }
}
