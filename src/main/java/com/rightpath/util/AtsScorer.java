package com.rightpath.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scores a resume against a whole job posting, the way an applicant tracking system does, not against its skills
 * list alone.
 *
 * <p>The score used to be only the share of the posting's key skills found in the resume. That ignores most of what
 * a posting says: the description of the work, the seniority asked for, the role itself and the education. A
 * resume that names every listed skill but has nothing to do with the work scored full marks, and an
 * otherwise strong resume that phrased the skills differently scored nothing.</p>
 *
 * <p>Five parts, each 0 to 100, combined by weight:</p>
 * <ul>
 *   <li><b>Skills (50%)</b>: the share of the listed key skills the resume covers, with synonyms. The heaviest part,
 *       because the skills list is what the recruiter wrote down as non-negotiable.</li>
 *   <li><b>Description (20%)</b>: how much of the vocabulary of the job description the resume uses, weighted by how
 *       often the description stresses each term. Half the description's distinctive terms is full marks, because
 *       no resume repeats a whole posting.</li>
 *   <li><b>Experience (15%)</b>: the years the resume states against the years the posting asks for. A posting for
 *       freshers asks for none, so it is full marks.</li>
 *   <li><b>Role (10%)</b>: whether the resume speaks of the role or title being hired for.</li>
 *   <li><b>Education (5%)</b>: whether the resume holds a degree at the level asked for, or higher.</li>
 * </ul>
 *
 * <p>A part the posting gives nothing to measure (no key skills listed, no description, no experience stated) is
 * left out and the weights of the others are scaled up to fill its place, so an incomplete posting is not scored as
 * if the candidate had failed on it.</p>
 */
public final class AtsScorer {

    static final double WEIGHT_SKILLS = 50;
    static final double WEIGHT_DESCRIPTION = 20;
    static final double WEIGHT_EXPERIENCE = 15;
    static final double WEIGHT_ROLE = 10;
    static final double WEIGHT_EDUCATION = 5;

    /** Share of the description's weighted terms a resume must use to earn the whole description part. */
    private static final double FULL_CREDIT_DESCRIPTION_COVERAGE = 0.5;
    /** Share of the role's words a resume must use to earn the whole role part. */
    private static final double FULL_CREDIT_ROLE_COVERAGE = 0.5;
    /** What a resume earns on experience when it states no years at all but the posting asks for some. */
    private static final double UNSTATED_EXPERIENCE_CREDIT = 40;

    private static final Pattern NOT_ALNUM = Pattern.compile("[^a-z0-9+#]+");

    /** Words that say nothing about the work: they appear in every posting and every resume. */
    private static final Set<String> GENERIC = Set.of(
            "the", "and", "for", "with", "you", "your", "our", "are", "will", "have", "has", "from", "that", "this",
            "their", "they", "who", "all", "any", "can", "etc", "per", "via", "also", "such", "using", "use", "used",
            "job", "role", "work", "working", "works", "years", "year", "experience", "experienced", "candidate",
            "candidates", "required", "requirements", "requirement", "responsibilities", "responsibility",
            "description", "ability", "able", "strong", "good", "excellent", "knowledge", "skills", "skill",
            "understanding", "team", "teams", "company", "should", "must", "preferred", "plus", "looking", "seeking",
            "join", "opportunity", "including", "include", "within", "across", "well", "new", "high", "quality",
            "based", "related", "relevant", "hands", "proficiency", "proficient", "familiarity", "familiar",
            "responsible", "develop", "developing", "developed", "development", "design", "designing", "build",
            "building", "maintain", "maintaining", "ensure", "ensuring", "support", "supporting", "perform",
            "communication", "written", "verbal", "degree", "bachelor", "master", "freshers", "fresher",
            "apply", "applicants", "applicant", "position", "positions", "duties", "day", "days", "month", "months");

    private AtsScorer() {
    }

    /** One part of the score. */
    public record Component(String name, double score, double weight, String note) {
    }

    /** The overall percentage, and what it was made of. */
    public record Result(double percent, List<Component> components) {
    }

    /** The job's fields that bear on a score, so this class does not depend on the entity. */
    public record Posting(String keySkills, String description, String experience, String education, String title,
            String role) {
    }

    public static Result score(String resumeText, Posting posting, Map<String, List<String>> synonyms) {
        List<Component> parts = new ArrayList<>();
        if (resumeText == null || resumeText.isBlank() || posting == null) {
            return new Result(0.0, parts);
        }
        String lower = resumeText.toLowerCase(Locale.ROOT);
        String words = " " + NOT_ALNUM.matcher(lower).replaceAll(" ").trim() + " ";
        String squashed = words.replace(" ", "");

        // Skills.
        List<String> skills = SkillMatcher.parseSkills(posting.keySkills());
        if (!skills.isEmpty()) {
            double pct = SkillMatcher.matchPercent(resumeText, skills, synonyms == null ? Map.of() : synonyms);
            parts.add(new Component("Skills", pct, WEIGHT_SKILLS, "Key skills covered by the resume"));
        }

        // Description.
        Map<String, Integer> terms = distinctiveTerms(posting.description());
        if (!terms.isEmpty()) {
            double total = 0;
            double covered = 0;
            for (Map.Entry<String, Integer> term : terms.entrySet()) {
                total += term.getValue();
                if (contains(words, squashed, term.getKey())) {
                    covered += term.getValue();
                }
            }
            double coverage = total <= 0 ? 0 : covered / total;
            double pct = Math.min(1.0, coverage / FULL_CREDIT_DESCRIPTION_COVERAGE) * 100;
            parts.add(new Component("Description", pct, WEIGHT_DESCRIPTION,
                    "Job description terms used in the resume"));
        }

        // Experience.
        Integer requiredYears = requiredYears(posting.experience(), posting.description());
        if (requiredYears != null) {
            double pct;
            if (requiredYears <= 0) {
                pct = 100;
            } else {
                Integer stated = statedYears(lower);
                pct = stated == null ? UNSTATED_EXPERIENCE_CREDIT : Math.min(1.0, (double) stated / requiredYears) * 100;
            }
            parts.add(new Component("Experience", pct, WEIGHT_EXPERIENCE, "Years stated against years asked for"));
        }

        // Role.
        List<String> roleWords = roleWords(posting.title(), posting.role());
        if (!roleWords.isEmpty()) {
            long hit = roleWords.stream().filter(w -> contains(words, squashed, w)).count();
            double coverage = (double) hit / roleWords.size();
            double pct = Math.min(1.0, coverage / FULL_CREDIT_ROLE_COVERAGE) * 100;
            parts.add(new Component("Role", pct, WEIGHT_ROLE, "Role and title words found in the resume"));
        }

        // Education.
        int requiredLevel = educationLevel(posting.education());
        if (requiredLevel > 0) {
            int held = educationLevel(lower);
            parts.add(new Component("Education", held >= requiredLevel ? 100 : 0, WEIGHT_EDUCATION,
                    "Degree level asked for, or higher"));
        }

        double weights = parts.stream().mapToDouble(Component::weight).sum();
        if (weights <= 0) {
            return new Result(0.0, parts);
        }
        double weighted = parts.stream().mapToDouble(p -> p.score() * p.weight()).sum();
        double percent = Math.round(weighted / weights * 10.0) / 10.0;
        return new Result(Math.max(0, Math.min(100, percent)), parts);
    }

    // ---------------------------------------------------------------- text helpers

    private static boolean contains(String words, String squashed, String term) {
        String spaced = NOT_ALNUM.matcher(term).replaceAll(" ").trim();
        String compact = spaced.replace(" ", "");
        if (compact.isEmpty()) {
            return false;
        }
        if (compact.length() <= 3) {
            return words.contains(" " + spaced + " ");
        }
        if (squashed.contains(compact)) {
            return true;
        }
        // "deploying" and "deployed" count for "deployment": compare on a stem a few letters shorter.
        String stem = stem(compact);
        return stem.length() >= 5 && squashed.contains(stem);
    }

    private static String stem(String word) {
        for (String suffix : new String[] { "ations", "ation", "ments", "ment", "ings", "ing", "ed", "es", "s" }) {
            if (word.length() - suffix.length() >= 5 && word.endsWith(suffix)) {
                return word.substring(0, word.length() - suffix.length());
            }
        }
        return word;
    }

    /** Terms from the description that say something about the work, with how often it repeats them. */
    private static Map<String, Integer> distinctiveTerms(String description) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        if (description == null || description.isBlank()) {
            return terms;
        }
        for (String token : NOT_ALNUM.split(description.toLowerCase(Locale.ROOT))) {
            if (token.length() < 3 || GENERIC.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            terms.merge(token, 1, Integer::sum);
        }
        return terms;
    }

    private static List<String> roleWords(String title, String role) {
        List<String> words = new ArrayList<>();
        for (String text : new String[] { title, role }) {
            if (text == null) {
                continue;
            }
            for (String token : NOT_ALNUM.split(text.toLowerCase(Locale.ROOT))) {
                if (token.length() >= 3 && !GENERIC.contains(token) && !words.contains(token)) {
                    words.add(token);
                }
            }
        }
        return words;
    }

    // ---------------------------------------------------------------- experience

    private static final Pattern RANGE = Pattern.compile("(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})");
    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})(?:\\.\\d)?\\s*\\+?\\s*(?:years?|yrs?)");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d{1,2})\\s*\\+?\\s*$");

    /**
     * The minimum years the posting asks for: "0-2 Years (Freshers can apply)" is 0, "3+ years" is 3, "Fresher" is
     * 0, "1" is 1. Null when the posting states nothing to measure against.
     */
    static Integer requiredYears(String experienceField, String description) {
        if (experienceField != null && !experienceField.isBlank()) {
            String text = experienceField.toLowerCase(Locale.ROOT).trim();
            if (text.contains("fresher") && !RANGE.matcher(text).find() && !YEARS.matcher(text).find()) {
                return 0;
            }
            Matcher range = RANGE.matcher(text);
            if (range.find()) {
                return Integer.parseInt(range.group(1));
            }
            Matcher years = YEARS.matcher(text);
            if (years.find()) {
                return Integer.parseInt(years.group(1));
            }
            Matcher bare = BARE_NUMBER.matcher(text);
            if (bare.find()) {
                return Integer.parseInt(bare.group(1));
            }
            if (text.contains("fresher") || text.contains("entry") || text.contains("graduate")) {
                return 0;
            }
        }
        if (description != null) {
            Matcher years = YEARS.matcher(description.toLowerCase(Locale.ROOT));
            int highest = -1;
            while (years.find()) {
                highest = Math.max(highest, Integer.parseInt(years.group(1)));
            }
            if (highest >= 0) {
                return highest;
            }
        }
        return null;
    }

    /** The most years of experience the resume claims in words ("5 years of experience"), or null if none. */
    static Integer statedYears(String lowerResume) {
        Matcher years = YEARS.matcher(lowerResume);
        int highest = -1;
        while (years.find()) {
            int value = Integer.parseInt(years.group(1));
            // A figure above forty is not a career length, it is something else (a year, a quantity).
            if (value <= 40) {
                highest = Math.max(highest, value);
            }
        }
        if (highest >= 0) {
            return highest;
        }
        return lowerResume.contains("fresher") ? 0 : null;
    }

    // ---------------------------------------------------------------- education

    /** 0 none or unrecognised, 1 bachelor's level, 2 master's level, 3 doctorate. */
    static int educationLevel(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String t = " " + NOT_ALNUM.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").trim() + " ";
        String squashed = t.replace(" ", "");
        int level = 0;
        for (String key : new String[] { "btech", "b e", "bsc", "bca", "bcom", "bba", "bachelor", "graduate", "degree" }) {
            if ((key.length() <= 3 || key.contains(" ")) ? t.contains(" " + key + " ") : squashed.contains(key)) {
                level = Math.max(level, 1);
            }
        }
        for (String key : new String[] { "mtech", "m e", "msc", "mca", "mba", "master", "postgraduate" }) {
            if ((key.length() <= 3 || key.contains(" ")) ? t.contains(" " + key + " ") : squashed.contains(key)) {
                level = Math.max(level, 2);
            }
        }
        if (squashed.contains("phd") || squashed.contains("doctorate")) {
            level = 3;
        }
        // "any degree" and similar ask for nothing particular.
        if (t.contains(" any ") && level == 1) {
            return 0;
        }
        return level;
    }
}
