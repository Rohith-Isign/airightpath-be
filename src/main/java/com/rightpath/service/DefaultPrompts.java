package com.rightpath.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.rightpath.enums.PromptStage;
import com.rightpath.enums.PromptType;

/**
 * The prompts a job uses until someone writes its own.
 *
 * <p>Held in the jar, under {@code prompts/defaults/}, so a fresh install with an empty database can already
 * generate questions and run interviews. A prompt saved for a job always wins; these answer only where a job has
 * none. The same text is offered to the Manage AI Prompts screen, so an administrator sees what a job is using
 * and edits it from there, rather than finding out that a hidden script was answering.</p>
 *
 * <p>Six slots: aptitude, coding, and the start and summary prompts of the L2 technical and L3 behavioural
 * rounds. The round-agnostic {@link PromptType#INTERVIEW} prompt, which jobs set up before rounds existed still
 * carry, is answered with the L2 prompts, since L2 is the default round.</p>
 */
@Component
public class DefaultPrompts {

    private static final Logger log = LoggerFactory.getLogger(DefaultPrompts.class);

    /** One built-in prompt, as the screen lists it. */
    public record Entry(PromptType promptType, PromptStage promptStage, String prompt) {}

    private static final String DIR = "prompts/defaults/";

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public DefaultPrompts() {
        load(PromptType.APTITUDE, null, "aptitude.txt");
        load(PromptType.CODING, null, "coding.txt");
        load(PromptType.INTERVIEW_L2_TECHNICAL, PromptStage.START, "interview-l2-start.txt");
        load(PromptType.INTERVIEW_L2_TECHNICAL, PromptStage.SUMMARY, "interview-l2-summary.txt");
        load(PromptType.INTERVIEW_L3_BEHAVIORAL, PromptStage.START, "interview-l3-start.txt");
        load(PromptType.INTERVIEW_L3_BEHAVIORAL, PromptStage.SUMMARY, "interview-l3-summary.txt");
    }

    /** The built-in prompt for a slot, if there is one. */
    public Optional<String> find(PromptType type, PromptStage stage) {
        // The shared interview prompt stands for the default round, which is the technical one.
        PromptType effective = type == PromptType.INTERVIEW ? PromptType.INTERVIEW_L2_TECHNICAL : type;
        Entry entry = entries.get(key(effective, stage));
        return entry == null ? Optional.empty() : Optional.of(entry.prompt());
    }

    /** Every built-in prompt, for the Manage AI Prompts screen. */
    public List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    private void load(PromptType type, PromptStage stage, String file) {
        try (InputStream in = new ClassPathResource(DIR + file).getInputStream()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
            entries.put(key(type, stage), new Entry(type, stage, text));
        } catch (IOException e) {
            // Reported, not thrown: a missing default must not stop the application starting. The slot then
            // behaves as it did before defaults existed, and refuses with the "configure a prompt" message.
            log.error("Could not load the default prompt {}{}", DIR, file, e);
        }
    }

    private static String key(PromptType type, PromptStage stage) {
        return type + "/" + (stage == null ? "-" : stage);
    }
}
