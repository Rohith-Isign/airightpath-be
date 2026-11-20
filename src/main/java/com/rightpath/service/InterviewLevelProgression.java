package com.rightpath.service;

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
 *   <li>A skill moves up a level after {@code interview.level.passes-to-advance} correct answers at its current level
 *       (default 1). Passing Expert finishes the skill.</li>
 *   <li>A skill stops at its current level after {@code interview.level.misses-to-stop} answers there that are not
 *       correct (default 2), so one slip does not end it but a skill the candidate plainly cannot take further is not
 *       pushed on. Stopping one skill never ends the interview.</li>
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

    @Value("${interview.level.passes-to-advance:1}")
    private int passesToAdvance = 1;

    @Value("${interview.level.misses-to-stop:2}")
    private int missesToStop = 2;

    /** Where one skill stands. */
    public static final class SkillState {
        /** Index into {@link #LEVELS} of the level being asked now. */
        int current;
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
            if (entry.getRole() != ConversationRole.CANDIDATE || topic == null || level == null) {
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
            int asked = LEVELS.indexOf(level);
            if (asked < state.current) {
                continue; // A question below the level already reached proves nothing new.
            }
            boolean correct = !skipped && entry.getAnswerScore() >= passScore;
            if (correct) {
                // A good answer to a question above the current level counts for that level, so a model that jumped
                // ahead is not made to walk back down.
                state.current = asked;
                state.passes++;
                state.misses = 0;
                if (state.passes >= passesToAdvance) {
                    if (state.current == LEVELS.size() - 1) {
                        state.finished = true;
                    } else {
                        state.current++;
                        state.passes = 0;
                    }
                }
            } else {
                state.misses++;
                if (state.misses >= missesToStop) {
                    state.stopped = true;
                }
            }
        }
        return skills;
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
                sb.append("ask at ").append(state.currentLevel()).append(" next");
                if (state.misses > 0) {
                    sb.append(" (one answer at this level was not correct; one more that is not ends this skill)");
                }
                sb.append('.');
            }
            sb.append('\n');
        });
        sb.append("Skills not listed have not been rated yet and start at BASIC. The answer you are rating in this ")
                .append("reply is not counted above: if you rate it ").append(passScore)
                .append(" or more, ask that skill one level higher next; below ").append(passScore)
                .append(", stay at the same level or move to another skill. Tag every question's [LEVEL] to match.");
        return sb.toString();
    }

    // ---------------------------------------------------------------- sequence and completion

    private static final java.util.regex.Pattern CODING = java.util.regex.Pattern.compile(
            "^\\s*\\[\\s*CODING\\s*\\]", java.util.regex.Pattern.CASE_INSENSITIVE);

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

    private static String squash(String value) {
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
    }

    /**
     * The skill to ask about now: the one already in progress, until it is finished or stopped; then the next required
     * skill not yet started. One skill at a time, all its levels, before the next.
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
        if (lastAsked != null && !isDone(states.get(lastAsked))) {
            return lastAsked;
        }
        return required.stream().filter(s -> !isDone(states.get(s))).filter(s -> !states.containsKey(s))
                .findFirst()
                .orElseGet(() -> required.stream().filter(s -> !isDone(states.get(s))).findFirst().orElse(null));
    }

    private static boolean isDone(SkillState state) {
        return state != null && (state.finished || state.stopped);
    }

    /** Required skills not yet finished or stopped: what still has to be assessed before the interview may close. */
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
     * every required skill finished or stopped, and the coding tasks done.
     */
    public String completionGap(List<VoiceConversationEntry> entries, List<String> required, int minCodingTasks) {
        List<String> open = unfinished(entries, required);
        int coding = distinctCodingTasks(entries);
        if (open.isEmpty() && coding >= minCodingTasks) {
            return null;
        }
        StringBuilder sb = new StringBuilder("The assessment is not complete, so do NOT close the interview yet.");
        if (!open.isEmpty()) {
            sb.append(" Skills still to be taken through their levels (until they reach Expert or stop): ")
                    .append(String.join(", ", open)).append('.');
        }
        if (coding < minCodingTasks) {
            sb.append(" Distinct coding tasks done: ").append(coding).append(" of the ").append(minCodingTasks)
                    .append(" needed; a re-asked or corrected task does not count again.");
        }
        sb.append(" Ask the next question on what is missing now, with all its tags.");
        return sb.toString();
    }

    /**
     * The order instruction given every turn: which skill to ask about now, which come after it, and that no skill
     * outside the job's list may be used. Null when the job lists no skills.
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
            sb.append("Every skill has been taken as far as the candidate can go. Complete any coding requirement, ")
                    .append("then close.");
            return sb.toString();
        }
        SkillState state = states.get(current);
        sb.append("Ask your next question on ").append(current).append(" at ")
                .append(state == null ? "BASIC" : state.currentLevel())
                .append(". Stay on ").append(current)
                .append(" until it reaches Expert or stops at a level; only then move to the next skill. ")
                .append("A follow-up must test a different competency at that level, never the same question again.");
        List<String> after = required.stream().filter(s -> !s.equals(current)).filter(s -> !isDone(states.get(s)))
                .toList();
        if (!after.isEmpty()) {
            sb.append("\nStill to come, in this order unless the resume shows a later one is the candidate's stronger ")
                    .append("skill: ").append(String.join(", ", after)).append('.');
        }
        return sb.toString();
    }
}
