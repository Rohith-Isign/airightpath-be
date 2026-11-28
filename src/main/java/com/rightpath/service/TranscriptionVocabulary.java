package com.rightpath.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.VoiceConversationEntry;
import com.rightpath.enums.ConversationRole;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.VoiceConversationEntryRepository;
import com.rightpath.util.PromptPlaceholderResolver;
import com.rightpath.util.TechVocabulary;

/**
 * The hint given to the speech model with every chunk of an interview: the candidate's name and the technical terms
 * they are likely to say, so it spells them as they are written ("PostgreSQL", not "post gress").
 *
 * <p>The terms come from the job's skills, the job description and the resume (worked out once per interview), and
 * from the question being answered (looked up again as the questions change), which is where most of the words of an
 * answer come from. The speech model reads only the end of a long hint, so the question's terms are put last.</p>
 */
@Component
public class TranscriptionVocabulary {

    private static final Logger log = LoggerFactory.getLogger(TranscriptionVocabulary.class);

    /** The speech model reads about the last 224 tokens of its hint; this keeps well inside that. */
    private static final int MAX_HINT_CHARS = 700;

    /** How long the question's terms are reused before the question is looked up again. */
    private static final long QUESTION_TTL_MS = 8_000;

    private final CandidateInterviewScheduleRepository scheduleRepo;
    private final VoiceConversationEntryRepository entryRepository;
    private final PromptPlaceholderResolver placeholderResolver;
    private final CandidateNameResolver nameResolver;

    private final Map<Long, Set<String>> jobTerms = new ConcurrentHashMap<>();
    private final Map<Long, CachedQuestion> questionTerms = new ConcurrentHashMap<>();

    private record CachedQuestion(Set<String> terms, long at) {
    }

    public TranscriptionVocabulary(CandidateInterviewScheduleRepository scheduleRepo,
            VoiceConversationEntryRepository entryRepository, PromptPlaceholderResolver placeholderResolver,
            CandidateNameResolver nameResolver) {
        this.scheduleRepo = scheduleRepo;
        this.entryRepository = entryRepository;
        this.placeholderResolver = placeholderResolver;
        this.nameResolver = nameResolver;
    }

    /** The hint for a chunk of this interview, or null when there is nothing to hint. */
    public String hint(Long scheduleId) {
        if (scheduleId == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        nameResolver.forInterview(scheduleId).ifPresent(name -> sb.append("The speaker's name is ").append(name)
                .append(". "));
        Set<String> terms = new LinkedHashSet<>(jobTerms(scheduleId));
        Set<String> fromQuestion = questionTerms(scheduleId);
        terms.removeAll(fromQuestion);
        terms.addAll(fromQuestion); // last, where the model pays most attention
        if (!terms.isEmpty()) {
            String list = String.join(", ", terms);
            int room = MAX_HINT_CHARS - sb.length() - 40;
            if (list.length() > room && room > 0) {
                // Keep the end of the list: the question's terms are there.
                list = list.substring(list.length() - room);
                list = list.substring(list.indexOf(", ") + 2);
            }
            sb.append("A technical interview answer mentioning ").append(list).append('.');
        }
        String hint = sb.toString().trim();
        return hint.isEmpty() ? null : hint;
    }

    /** Whether a transcript is only the hint said back, which the model does on a silent or very quiet chunk. */
    public static boolean echoesHint(String transcript, String hint) {
        if (transcript == null || hint == null) {
            return false;
        }
        String t = transcript.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        String h = hint.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return t.split(" ").length >= 2 && h.contains(t)
                || t.startsWith("a technical interview answer mentioning");
    }

    private Set<String> jobTerms(Long scheduleId) {
        return jobTerms.computeIfAbsent(scheduleId, id -> {
            try {
                CandidateInterviewSchedule schedule = scheduleRepo.findById(id).orElse(null);
                if (schedule == null) {
                    return Set.of();
                }
                String skills = placeholderResolver.resolveJobPlaceholders("{{skills}}", schedule.getJobPrefix());
                String text = placeholderResolver.resolveAllInterviewPlaceholders(
                        "{{skills}} {{jobDescription}} {{resumeSummary}}", schedule.getJobPrefix(),
                        schedule.getEmail(), schedule.getInterviewerName(), schedule.getEffectiveRound());
                Set<String> terms = new LinkedHashSet<>();
                // The job's own skill names first, as the job writes them.
                if (skills != null && !skills.contains("{{")) {
                    for (String skill : skills.split("[,;|\\n]")) {
                        if (!skill.isBlank() && skill.trim().length() <= 40) {
                            terms.add(skill.trim());
                        }
                    }
                }
                terms.addAll(TechVocabulary.termsIn(text));
                return terms;
            } catch (Exception e) {
                log.debug("No vocabulary for interview {}", id, e);
                return Set.of();
            }
        });
    }

    private Set<String> questionTerms(Long scheduleId) {
        CachedQuestion cached = questionTerms.get(scheduleId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < QUESTION_TTL_MS) {
            return cached.terms();
        }
        Set<String> terms = Set.of();
        try {
            List<VoiceConversationEntry> entries = entryRepository.findByInterviewScheduleIdOrderByTimestampAsc(
                    scheduleId);
            for (int i = entries.size() - 1; i >= 0; i--) {
                if (entries.get(i).getRole() == ConversationRole.INTERVIEWER) {
                    terms = TechVocabulary.termsIn(entries.get(i).getContent());
                    break;
                }
            }
        } catch (Exception e) {
            log.debug("No question terms for interview {}", scheduleId, e);
        }
        questionTerms.put(scheduleId, new CachedQuestion(terms, now));
        return terms;
    }
}
