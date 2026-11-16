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
        Map<String, SkillState> skills = new LinkedHashMap<>();
        String topic = null;
        String level = null;
        for (VoiceConversationEntry entry : entries) {
            if (entry.getRole() == ConversationRole.INTERVIEWER) {
                if (entry.getTopic() != null && !entry.getTopic().isBlank()) {
                    topic = entry.getTopic().trim();
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
            SkillState state = skills.computeIfAbsent(topic, t -> new SkillState());
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
        Map<String, SkillState> skills = compute(entries);
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
}
