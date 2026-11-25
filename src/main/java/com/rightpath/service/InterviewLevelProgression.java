package com.rightpath.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;

/**
 * Decides, from the interviewer's own ratings, whether each answer counts as correct and how far each skill has got
 * through Basic, Intermediate, Advanced and Expert.
 *
 * <p>The model rates every answer 0-10 and labels every question with a skill and a level. It used to be left to
 * decide for itself when a skill had earned the next level, which it did loosely and inconsistently. The rule is now
 * a number the server applies the same way every time:</p>
 * <ul>
 *   <li>An answer is <b>correct</b> when it is rated at or above {@code interview.answer.pass-score} (default 6/10).
 *       A skipped question counts as not correct.</li>
 *   <li>Each level of each skill is asked {@code interview.level.questions-per-level} questions (default 5), and is
 *       passed when {@code interview.level.pass-ratio} of them are correct (default 0.6, so 3 of 5). Then the skill
 *       moves up; passing Expert finishes it.</li>
 *   <li>A skill stops at its current level as soon as passing it is out of reach (the third answer there that is not
 *       correct, at the defaults), so a level the candidate plainly cannot pass is not asked to the end. Stopping one
 *       skill never ends the interview.</li>
 * </ul>
 *
 * <p>The result is given to the interviewer every turn as an instruction (see {@link #buildDirective}). The model
 * still writes the question; the server decides the level it is asked at.</p>
 */
@Component
public class InterviewLevelProgression {

    /** The four levels, in order. */
    public static final List<String> LEVELS = List.of("BASIC", "INTERMEDIATE", "ADVANCED", "EXPERT");

    private static final String SKIPPED_CONTENT = "[NO RESPONSE - SKIPPED]";

    @Value("${interview.answer.pass-score:6}")
    private int passScore = 6;

    /**
     * Questions asked at each level of each skill before it is judged. Two or three per level was too little evidence
     * for a level, or for the report built on it; five is the default, and up to ten can be set.
     */
    @Value("${interview.level.questions-per-level:5}")
    private int questionsPerLevel = 5;

    /** The share of a level's questions that must be answered correctly to pass it: 0.6 is 3 of 5. */
    @Value("${interview.level.pass-ratio:0.6}")
    private double passRatio = 0.6;

    /** For tests: a level of this many questions, every one of which must be correct. */
    void setPassesToAdvance(int passes) {
        setLevelRule(passes, 1.0);
    }

    /** For tests. */
    void setLevelRule(int questions, double ratio) {
        this.questionsPerLevel = questions;
        this.passRatio = ratio;
    }

    /** Correct answers a level needs. */
    int passesNeeded() {
        return Math.max(1, (int) Math.ceil(Math.max(1, questionsPerLevel) * passRatio - 1e-9));
    }

    /** Answers at a level that may be wrong before passing it is out of reach, which stops the skill there. */
    int missesAllowed() {
        return Math.max(0, Math.max(1, questionsPerLevel) - passesNeeded());
    }

    /** Where one skill stands. */
    public static final class SkillState {
        /** Index into {@link #LEVELS} of the level being asked now. */
        int current;
        /** Questions answered (or skipped) at the current level, and how many of them correctly. */
        int asked;
        int passes;
        int misses;
        boolean stopped;
        boolean finished;

        public String currentLevel() {
            return LEVELS.get(current);
        }

        /** The highest level passed, or null when not even Basic has been. */
        public String highestPassed() {
            int passed = finished ? LEVELS.size() - 1 : current - 1;
            return passed >= 0 ? LEVELS.get(passed) : null;
        }

        public boolean isStopped() {
            return stopped;
        }

        public boolean isFinished() {
            return finished;
        }
    }

    public int passScore() {
        return passScore;
    }

    /** Whether a rating counts as a correct answer. Null (not rated) is unknown, not wrong. */
    public Boolean isCorrect(Integer score) {
        return score == null ? null : score >= passScore;
    }

    /**
     * Each skill's progress so far, in the order the skills were first asked about.
     *
     * <p>An answer belongs to the question before it. A follow-up carries no skill of its own, so it stays on the
     * skill last named. Questions with no skill or no level are not counted: they are older interviews, or turns the
     * model did not label.</p>
     */
    public Map<String, SkillState> compute(List<VoiceConversationEntry> entries) {
        return compute(entries, List.of());
    }

    /**
     * As above, with the job's required skills: a tagged skill is counted under the job's own name for it ("python 3"
     * under "Python"), so the same skill is never tracked twice under two spellings.
     */
    public Map<String, SkillState> compute(List<VoiceConversationEntry> entries, List<String> required) {
        Map<String, SkillState> skills = new LinkedHashMap<>();
        String topic = null;
        String skill = null;
        String level = null;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                if (entry.getTopic() != null && !entry.getTopic().isBlank()) {
                    topic = entry.getTopic().trim();
                }
                // Levels run per skill: Java can reach Expert while SQL stops at Basic, though both count towards
                // the same category. A follow-up stays on the skill it presses; a new question names its own, and
                // one that names none falls back to its category.
                boolean followUp = entry.getTurnKind() != null
                        && entry.getTurnKind() != com.rightpath.enums.TurnKind.NEW_QUESTION;
                if (entry.getSkill() != null && !entry.getSkill().isBlank()) {
                    String named = canonical(entry.getSkill(), required);
                    skill = named != null ? named : entry.getSkill().trim();
                } else if (!followUp) {
                    skill = null;
                }
                level = entry.getLevel();
                continue;
            }
            // A skill tag is enough: a question the model filed under no scoring category still counts for its skill.
            if (entry.getRole() != ConversationRole.CANDIDATE || (topic == null && skill == null) || level == null) {
                continue;
            }
            boolean skipped = entry.getContent() != null && SKIPPED_CONTENT.equals(entry.getContent().trim());
            if (!skipped && entry.getAnswerScore() == null) {
                continue; // Not rated yet: the rating arrives with the next reply.
            }
            SkillState state = skills.computeIfAbsent(skill != null ? skill : topic, t -> new SkillState());
            if (state.stopped || state.finished) {
                continue;
            }
            int askedAt = LEVELS.indexOf(level);
            if (askedAt < state.current) {
                continue; // A question below the level already reached proves nothing new.
            }
            boolean correct = !skipped && entry.getAnswerScore() >= passScore;
            if (askedAt > state.current) {
                if (!correct) {
                    continue; // The model jumped ahead and the answer missed: it says nothing about the level in hand.
                }
                // A good answer above the current level starts that level, so a model that jumped ahead is not made to
                // walk back down.
                state.current = askedAt;
                state.asked = 0;
                state.passes = 0;
                state.misses = 0;
            }
            state.asked++;
            if (correct) {
                state.passes++;
            } else {
                state.misses++;
            }
            // Each level is judged on its full set of questions; it stops early only once passing it is out of reach.
            if (state.misses > missesAllowed()) {
                state.stopped = true;
            } else if (state.asked >= Math.max(1, questionsPerLevel) && state.passes >= passesNeeded()) {
                if (state.current == LEVELS.size() - 1) {
                    state.finished = true;
                } else {
                    state.current++;
                    state.asked = 0;
                    state.passes = 0;
                    state.misses = 0;
                }
            }
        }
        return skills;
    }

    /**
     * The highest level of each skill the candidate actually demonstrated in the interview, from the answers as rated:
     * a level counts when at least {@link #passRatio} of its answers were correct, and stops at the first level that was
     * asked and not passed. NONE when not even the lowest level asked was passed. Only skills with rated, levelled
     * answers appear.
     *
     * <p>This is what the report's level cards show, level by level, so the headline level is taken from it too: the
     * grader's own pick ("Intermediate") sat above cards showing Advanced and Expert answered correctly.</p>
     */
    /**
     * Each skill's average answer rating, 0 to 10: the reviewer's rating where one was set, else the rating at the
     * time, a skipped question counting 0. Only skills with rated answers appear.
     */
    public Map<String, Double> averageRatings(List<VoiceConversationEntry> entries, List<String> required) {
        Map<String, double[]> sums = new LinkedHashMap<>(); // skill -> [total, count]
        String skill = null;
        boolean open = false;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                boolean followUp = entry.getTurnKind() != null
                        && entry.getTurnKind() != com.rightpath.enums.TurnKind.NEW_QUESTION;
                if (entry.getSkill() != null && !entry.getSkill().isBlank()) {
                    String named = canonical(entry.getSkill(), required);
                    skill = named != null ? named : entry.getSkill().trim();
                } else if (!followUp) {
                    skill = null;
                }
                open = entry.getLevel() != null;
                continue;
            }
            if (entry.getRole() != ConversationRole.CANDIDATE || skill == null || !open) {
                continue;
            }
            open = false;
            boolean skipped = entry.getContent() != null && SKIPPED_CONTENT.equals(entry.getContent().trim());
            Integer score = entry.getReviewedScore() != null ? entry.getReviewedScore() : entry.getAnswerScore();
            if (!skipped && score == null) {
                continue;
            }
            double[] sum = sums.computeIfAbsent(skill, s -> new double[2]);
            sum[0] += skipped ? 0 : score;
            sum[1]++;
        }
        Map<String, Double> averages = new LinkedHashMap<>();
        sums.forEach((name, sum) -> averages.put(name, sum[0] / sum[1]));
        return averages;
    }

    public Map<String, String> demonstratedLevels(List<VoiceConversationEntry> entries, List<String> required) {
        Map<String, int[][]> counts = new LinkedHashMap<>(); // skill -> [level][correct, judged]
        String skill = null;
        String level = null;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                boolean followUp = entry.getTurnKind() != null
                        && entry.getTurnKind() != com.rightpath.enums.TurnKind.NEW_QUESTION;
                if (entry.getSkill() != null && !entry.getSkill().isBlank()) {
                    String named = canonical(entry.getSkill(), required);
                    skill = named != null ? named : entry.getSkill().trim();
                } else if (!followUp) {
                    skill = null;
                }
                level = entry.getLevel();
                continue;
            }
            if (entry.getRole() != ConversationRole.CANDIDATE || skill == null || level == null
                    || !LEVELS.contains(level)) {
                continue;
            }
            boolean skipped = entry.getContent() != null && SKIPPED_CONTENT.equals(entry.getContent().trim());
            if (!skipped && entry.getAnswerScore() == null) {
                continue;
            }
            int[] cell = counts.computeIfAbsent(skill, s -> new int[LEVELS.size()][2])[LEVELS.indexOf(level)];
            if (!skipped && entry.getAnswerScore() >= passScore) {
                cell[0]++;
            }
            cell[1]++;
        }
        Map<String, String> demonstrated = new LinkedHashMap<>();
        counts.forEach((name, byLevel) -> {
            String highest = "NONE";
            for (int i = 0; i < LEVELS.size(); i++) {
                int correct = byLevel[i][0];
                int judged = byLevel[i][1];
                if (judged == 0) {
                    continue; // Not asked: a pass above it still counts, as the plan lets a skill jump ahead.
                }
                if (correct < judged * passRatio - 1e-9) {
                    break;
                }
                highest = LEVELS.get(i);
            }
            demonstrated.put(name, highest);
        });
        return demonstrated;
    }

    /**
     * The instruction given to the interviewer every turn: which level to ask each skill at next, and which skills are
     * finished. Null when nothing has been rated with a skill and level yet.
     */
    public String buildDirective(List<VoiceConversationEntry> entries) {
        return buildDirective(entries, List.of());
    }

    /** As above, counting each skill under the job's own name for it. */
    public String buildDirective(List<VoiceConversationEntry> entries, List<String> required) {
        Map<String, SkillState> skills = compute(entries, required);
        if (skills.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder()
                .append("LEVEL PROGRESS — worked out by the system from your [SCORE] ratings, where an answer counts ")
                .append("as correct at ").append(passScore).append("/10 or more. Follow it over your own judgement, ")
                .append("and NEVER tell the candidate their level or progress.\n");
        skills.forEach((skill, state) -> {
            sb.append("- ").append(skill).append(": ");
            if (state.finished) {
                sb.append("EXPERT passed. Finished: do not ask more about it.");
            } else if (state.stopped) {
                String passed = state.highestPassed();
                sb.append("stopped at ").append(state.currentLevel()).append(" (")
                        .append(passed == null ? "Basic not passed" : passed + " passed")
                        .append("). Finished: do not ask more about it; move to another skill.");
            } else {
                sb.append("ask at ").append(state.currentLevel()).append(" next (question ")
                        .append(Math.min(state.asked + 1, Math.max(1, questionsPerLevel))).append(" of ")
                        .append(Math.max(1, questionsPerLevel)).append(" at this level, ").append(state.passes)
                        .append(" correct so far");
                int missesLeft = missesAllowed() - state.misses;
                if (state.misses > 0) {
                    sb.append(missesLeft <= 0 ? "; one more that is not correct ends this skill"
                            : "; " + (missesLeft + 1) + " more that are not correct end this skill");
                }
                sb.append(").");
            }
            sb.append('\n');
        });
        sb.append("Skills not listed have not been rated yet and start at BASIC. The answer you are rating in this ")
                .append("reply is not counted above. Each level is ").append(Math.max(1, questionsPerLevel))
                .append(" questions, each on a different competency; it is passed with ").append(passesNeeded())
                .append(" or more correct, and only then does the skill go one level higher. Tag every question's ")
                .append("[LEVEL] to match.");
        return sb.toString();
    }

    // ---------------------------------------------------------------- sequence and completion

    private static final java.util.regex.Pattern CODING = java.util.regex.Pattern.compile(
            "^\\s*\\[\\s*CODING\\s*\\]", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** The default list of coding skills: programming languages, frameworks and libraries that are written as code. */
    static final String DEFAULT_CODING_SKILLS = "python,java,javascript,typescript,c++,c#,golang,kotlin,scala,ruby,php,"
            + "rust,swift,sql,mysql,postgresql,numpy,pandas,react,angular,vue,node,spring,django,flask,fastapi,html,css,"
            + "programming,data structures,algorithms,pytorch,tensorflow,scikit-learn,keras";

    /**
     * Which of the job's skills are coding skills, by name: a skill whose name contains one of these needs coding
     * tasks. Conceptual skills (Machine Learning, RAG, system design) are assessed by questions alone.
     */
    @Value("${interview.coding.skills:*}")
    private String codingSkills = "*";

    /**
     * Skills that never need a coding task, when every skill does ("*" above): soft skills, which have nothing to
     * write. A conceptual technical skill (Machine Learning, RAG) still gets one, in a fitting form: the logic, or a
     * sketch of the design.
     */
    @Value("${interview.coding.exclude-skills:communication,soft skill,leadership,teamwork,presentation,english,behavio}")
    private String codingExcluded = "communication,soft skill,leadership,teamwork,presentation,english,behavio";

    /** For tests: which skills need coding, as the setting gives them. */
    void setCodingSkills(String skills) {
        this.codingSkills = skills;
    }

    /** The levels at which a coding skill must have its own coding task, once the skill reaches them. */
    @Value("${interview.coding.levels:BASIC,INTERMEDIATE}")
    private String codingLevels = "BASIC,INTERMEDIATE";

    /**
     * The job's own name for a tagged skill, or null when it is none of the job's skills. Matched ignoring case and
     * punctuation, and either way round, so "Numpy" is "NumPy" and "Python programming" is "Python".
     */
    public static String canonical(String tagged, List<String> required) {
        if (tagged == null || tagged.isBlank() || required == null || required.isEmpty()) {
            return null;
        }
        String t = squash(tagged);
        for (String skill : required) {
            if (squash(skill).equals(t)) {
                return skill;
            }
        }
        for (String skill : required) {
            String s = squash(skill);
            if (!s.isEmpty() && (t.contains(s) || s.contains(t))) {
                return skill;
            }
        }
        return null;
    }

    static String squash(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
    }

    /** Whether a skill is written as code, so it needs coding tasks as well as questions. */
    public boolean isCodingSkill(String skill) {
        if (skill == null) {
            return false;
        }
        String s = squash(skill);
        for (String keyword : codingExcluded.split(",")) {
            String k = squash(keyword);
            if (k.length() >= 2 && s.contains(k)) {
                return false;
            }
        }
        if ("*".equals(codingSkills.trim())) {
            return true;
        }
        for (String keyword : codingSkills.split(",")) {
            String k = squash(keyword);
            if (k.length() >= 2 && s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private List<String> configuredCodingLevels() {
        return java.util.Arrays.stream(codingLevels.split(","))
                .map(level -> level.trim().toUpperCase(java.util.Locale.ROOT))
                .filter(LEVELS::contains)
                .toList();
    }

    /** The levels a skill has been asked at. */
    private static java.util.Set<String> levelsAsked(List<VoiceConversationEntry> entries, List<String> required,
            String skill) {
        java.util.Set<String> levels = new java.util.HashSet<>();
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER && entry.getLevel() != null
                    && skill.equals(canonical(entry.getSkill(), required))) {
                levels.add(entry.getLevel());
            }
        }
        return levels;
    }

    /** The levels at which a skill has had a distinct coding task that was answered. */
    private static java.util.Set<String> codingDoneAt(List<VoiceConversationEntry> entries, List<String> required,
            String skill) {
        java.util.Set<String> done = new java.util.HashSet<>();
        String openLevel = null;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                boolean coding = entry.getContent() != null && CODING.matcher(entry.getContent()).find();
                boolean fresh = entry.getTurnKind() == null
                        || entry.getTurnKind() == com.rightpath.enums.TurnKind.NEW_QUESTION;
                openLevel = coding && fresh && skill.equals(canonical(entry.getSkill(), required))
                        ? entry.getLevel() : null;
            } else if (openLevel != null && entry.getRole() == ConversationRole.CANDIDATE) {
                boolean skipped = entry.getContent() != null && SKIPPED_CONTENT.equals(entry.getContent().trim());
                if (!skipped) {
                    done.add(openLevel);
                }
                openLevel = null;
            }
        }
        return done;
    }

    /**
     * The levels at which a coding skill still owes a coding task: each configured coding level the skill has reached
     * (been asked at) without a distinct, answered coding task there. Empty for a skill that is not a coding skill.
     */
    public List<String> codingOwed(List<VoiceConversationEntry> entries, List<String> required, String skill) {
        if (!isCodingSkill(skill)) {
            return List.of();
        }
        java.util.Set<String> asked = levelsAsked(entries, required, skill);
        java.util.Set<String> done = codingDoneAt(entries, required, skill);
        return configuredCodingLevels().stream().filter(asked::contains).filter(l -> !done.contains(l)).toList();
    }

    /** The competencies already tested for a skill, as the interviewer named them, in order. */
    public static List<String> competenciesTested(List<VoiceConversationEntry> entries, List<String> required,
            String skill) {
        List<String> tested = new ArrayList<>();
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER && entry.getCompetency() != null
                    && !entry.getCompetency().isBlank() && skill.equals(canonical(entry.getSkill(), required))
                    && tested.stream().noneMatch(c -> squash(c).equals(squash(entry.getCompetency())))) {
                tested.add(entry.getCompetency().trim());
            }
        }
        return tested;
    }

    private static boolean isDone(SkillState state) {
        return state != null && (state.finished || state.stopped);
    }

    /** A skill is complete when its levels are done (Expert, or stopped) and it owes no coding task. */
    private boolean isComplete(List<VoiceConversationEntry> entries, List<String> required,
            Map<String, SkillState> states, String skill) {
        return isDone(states.get(skill)) && codingOwed(entries, required, skill).isEmpty();
    }

    /**
     * The skill to ask about now: the one already in progress, until its levels are done and it owes no coding task;
     * then the next required skill not yet started. One skill at a time, all its levels and its coding, before the next.
     */
    String currentSkill(List<VoiceConversationEntry> entries, List<String> required, Map<String, SkillState> states) {
        String lastAsked = null;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER && entry.getSkill() != null) {
                String named = canonical(entry.getSkill(), required);
                if (named != null) {
                    lastAsked = named;
                }
            }
        }
        if (lastAsked != null && !isComplete(entries, required, states, lastAsked)) {
            return lastAsked;
        }
        return required.stream().filter(s -> !isComplete(entries, required, states, s))
                .filter(s -> !states.containsKey(s))
                .findFirst()
                .orElseGet(() -> required.stream().filter(s -> !isComplete(entries, required, states, s)).findFirst()
                        .orElse(null));
    }

    /** What the next new question must be: its skill, its level, and whether it has to be a coding task. */
    public record Expected(String skill, String level, boolean coding) {
    }

    /**
     * The next question the plan calls for, or null when every skill is complete. The skill in progress stays until it
     * is complete; a new skill starts at Basic; and a coding skill's coding task at a level comes as soon as the level
     * has had its first question, so each level has one.
     */
    public Expected expectedNext(List<VoiceConversationEntry> entries, List<String> required) {
        if (required == null || required.isEmpty()) {
            return null;
        }
        Map<String, SkillState> states = compute(entries, required);
        String current = currentSkill(entries, required, states);
        if (current == null) {
            return null;
        }
        List<String> owed = codingOwed(entries, required, current);
        if (!owed.isEmpty()) {
            return new Expected(current, owed.get(0), true);
        }
        SkillState state = states.get(current);
        return new Expected(current, state == null ? "BASIC" : state.currentLevel(), false);
    }

    /** Required skills whose levels are not yet done: what still has to be assessed before the interview may close. */
    public List<String> unfinished(List<VoiceConversationEntry> entries, List<String> required) {
        Map<String, SkillState> states = compute(entries, required);
        return required.stream().filter(s -> !isDone(states.get(s))).toList();
    }

    /**
     * Distinct coding tasks asked and answered. A coding question re-asked or followed up (a correction or recovery
     * attempt) is not a new task, so it does not count again.
     */
    public static int distinctCodingTasks(List<VoiceConversationEntry> entries) {
        int count = 0;
        boolean open = false;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                boolean coding = entry.getContent() != null && CODING.matcher(entry.getContent()).find();
                boolean fresh = entry.getTurnKind() == null
                        || entry.getTurnKind() == com.rightpath.enums.TurnKind.NEW_QUESTION;
                open = coding && fresh;
            } else if (open && entry.getRole() == ConversationRole.CANDIDATE) {
                boolean skipped = entry.getContent() != null && SKIPPED_CONTENT.equals(entry.getContent().trim());
                if (!skipped) {
                    count++;
                }
                open = false;
            }
        }
        return count;
    }

    /**
     * What is still missing before the interview may close, in words for the interviewer, or null when it is complete:
     * every required skill taken through its levels, every coding skill's coding tasks at the levels it reached, and
     * the minimum number of distinct coding tasks overall.
     */
    public String completionGap(List<VoiceConversationEntry> entries, List<String> required, int minCodingTasks) {
        List<String> open = unfinished(entries, required);
        List<String> codingGaps = new ArrayList<>();
        for (String skill : required) {
            List<String> owed = codingOwed(entries, required, skill);
            if (!owed.isEmpty()) {
                codingGaps.add(skill + " at " + String.join(" and ", owed));
            }
        }
        int coding = distinctCodingTasks(entries);
        if (open.isEmpty() && codingGaps.isEmpty() && coding >= minCodingTasks) {
            return null;
        }
        StringBuilder sb = new StringBuilder("The assessment is not complete, so do NOT close the interview yet.");
        if (!open.isEmpty()) {
            sb.append(" Skills still to be taken through their levels (until they reach Expert or stop): ")
                    .append(String.join(", ", open)).append('.');
        }
        if (!codingGaps.isEmpty()) {
            sb.append(" Coding tasks still owed ([CODING], tagged with the skill and level): ")
                    .append(String.join("; ", codingGaps)).append('.');
        }
        if (coding < minCodingTasks) {
            sb.append(" Distinct coding tasks done: ").append(coding).append(" of the ").append(minCodingTasks)
                    .append(" needed; a re-asked or corrected task does not count again.");
        }
        sb.append(" Ask the next question on what is missing now, with all its tags.");
        return sb.toString();
    }

    /**
     * The order instruction given every turn: which skill to ask about now and at what level, the coding it still
     * owes, the competencies already tested for it, which skills come after it, and that no skill outside the job's
     * list may be used. Null when the job lists no skills.
     */
    public String buildSequenceDirective(List<VoiceConversationEntry> entries, List<String> required) {
        if (required == null || required.isEmpty()) {
            return null;
        }
        Map<String, SkillState> states = compute(entries, required);
        String current = currentSkill(entries, required, states);
        StringBuilder sb = new StringBuilder("ASSESSMENT SEQUENCE — decided by the system; follow it exactly.\n")
                .append("The job's skills are: ").append(String.join(", ", required))
                .append(". Tag [SKILL] with one of these names only. Never assess a skill or domain outside this list ")
                .append("(scoring categories such as Logical Reasoning are not skills to interview on).\n");
        if (current == null) {
            sb.append("Every skill has been taken as far as the candidate can go and its coding is done. Complete any ")
                    .append("remaining coding requirement, then close.");
            return sb.toString();
        }
        SkillState state = states.get(current);
        List<String> owed = codingOwed(entries, required, current);
        Expected next = expectedNext(entries, required);
        if (next != null) {
            sb.append("YOUR NEXT NEW QUESTION: ").append(next.skill()).append(" at ").append(next.level())
                    .append(next.coding() ? ", as a coding task ([CODING], with its Task type)" : "")
                    .append(". Tag it [SKILL:").append(next.skill()).append("] [LEVEL:").append(next.level())
                    .append("]. A question that does not match is sent back.\n")
                    .append("Each level of each skill is ").append(Math.max(1, questionsPerLevel))
                    .append(" new questions (coding tasks included), each on a different competency. A level is ")
                    .append("passed with ").append(passesNeeded()).append(" or more correct; more than ")
                    .append(missesAllowed()).append(" not correct ends the skill at that level. A new skill always ")
                    .append("starts at BASIC.\n");
        }
        if (isDone(state) && !owed.isEmpty()) {
            sb.append(current).append(" has reached its last level, but it still owes a coding task at ")
                    .append(String.join(" and ", owed)).append(". Give that coding task now ([CODING], [SKILL:")
                    .append(current).append("], [LEVEL:").append(owed.get(0)).append("]) before moving on.");
        } else {
            sb.append("Ask your next question on ").append(current).append(" at ")
                    .append(state == null ? "BASIC" : state.currentLevel())
                    .append(". Stay on ").append(current)
                    .append(" until it reaches Expert or stops at a level; only then move to the next skill.");
            if (isCodingSkill(current)) {
                sb.append(" ").append(current).append(" is a coding skill: it needs its own coding task ([CODING], ")
                        .append("tagged with this skill and the level) at each of ")
                        .append(String.join(", ", configuredCodingLevels()))
                        .append(" that it reaches");
                sb.append(owed.isEmpty() ? "; none is owed right now." : "; still owed at: " + String.join(", ", owed) + ".");
            }
        }
        List<String> tested = competenciesTested(entries, required, current);
        sb.append("\nName what each question tests in [COMPETENCY:...]. A new question must test a competency not ")
                .append("yet tested for ").append(current);
        sb.append(tested.isEmpty() ? "." : ". Already tested: " + String.join("; ", tested) + ".");
        List<String> after = required.stream().filter(s -> !s.equals(current))
                .filter(s -> !isComplete(entries, required, states, s)).toList();
        if (!after.isEmpty()) {
            sb.append("\nStill to come, in this order unless the resume shows a later one is the candidate's stronger ")
                    .append("skill: ").append(String.join(", ", after)).append('.');
        }
        return sb.toString();
    }
}
