package com.rightpath.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import com.rightpath.dto.EffectiveInterviewTemplate;
import com.rightpath.dto.EvaluationCategorySaveRequest;
import com.rightpath.dto.InterviewTemplateRequest;
import com.rightpath.dto.JobPromptRequest;
import com.rightpath.entity.EvaluationCategory;
import com.rightpath.entity.InterviewTemplate;
import com.rightpath.entity.JobPrompt;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.EvaluationCategoryRepository;
import com.rightpath.service.InterviewTemplateService;
import com.rightpath.service.JobPromptService;
import com.rightpath.util.EvaluationCategoryFormatter;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class JobPromptController {

    private final JobPromptService jobPromptService;
    private final EvaluationCategoryRepository evaluationCategoryRepository;
    private final EvaluationCategoryFormatter categoryFormatter;
    private final InterviewTemplateService templateService;
    private final com.rightpath.service.JobPromptCopyService copyService;
    private final com.rightpath.service.DefaultPrompts defaultPrompts;

    /**
     * The built-in prompts, for the Manage AI Prompts screen.
     *
     * <p>What a job uses until it is given a prompt of its own, so the screen can show the text rather than an
     * empty box, and offer to reset a job back to it.</p>
     */
    @GetMapping("/defaults")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<List<com.rightpath.service.DefaultPrompts.Entry>> getDefaultPrompts() {
        return ResponseEntity.ok(defaultPrompts.all());
    }

    /** Which jobs already have prompts, and of what kind: the list a new job picks one to reuse from. */
    @GetMapping("/jobs-with-prompts")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<Map<String, Set<String>>> jobsWithPrompts() {
        return ResponseEntity.ok(copyService.jobsWithPrompts());
    }

    /**
     * Reuse another job's prompts, evaluation categories and interview settings for this one, in one step.
     * Body: {@code {"fromJobPrefix": "...", "toJobPrefix": "..."}}.
     */
    @PostMapping("/copy")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<Map<String, Integer>> copyPrompts(@RequestBody Map<String, String> request) {
        return ResponseEntity.ok(copyService.copy(request.get("fromJobPrefix"), request.get("toJobPrefix")));
    }

    @GetMapping("/{prefix}")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<List<JobPrompt>> getPromptsByPrefix(@PathVariable String prefix) {
        List<JobPrompt> prompts = jobPromptService.getPromptsByJobPrefix(prefix);
        return ResponseEntity.ok(prompts);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<JobPrompt> savePrompt(
            @RequestBody JobPromptRequest request) {

        JobPrompt savedPrompt = jobPromptService.saveJobPrompt(request);
        return ResponseEntity.ok(savedPrompt);
    }

    /**
     * A job's evaluation categories, for one round or for all of them.
     *
     * <p>Without {@code round} this returns every row the job has, which is what
     * the console listed before rounds could be scored separately. With one, it
     * returns that round's own list — and nothing when the round has none, so
     * the console can tell "this round is scored on the shared list" apart from
     * "this round has its own list that happens to look the same".</p>
     */
    @GetMapping("/evaluation-categories/{prefix}")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<List<EvaluationCategory>> getEvaluationCategories(
            @PathVariable String prefix,
            @RequestParam(required = false) InterviewRound round) {
        List<EvaluationCategory> categories = round == null
                ? evaluationCategoryRepository.findAllByJobPrefix(prefix)
                : evaluationCategoryRepository.findAllByJobPrefixAndRound(prefix, round);
        return ResponseEntity.ok(categories);
    }

    /**
     * What a round is actually scored on, defaults included.
     *
     * <p>Separate from the endpoint above because they answer different
     * questions. That one says what the job has stored; this one says what an
     * interview would use — the round's list, else the shared list, else the
     * platform defaults for that round. A recruiter who has configured nothing
     * still needs to see the seven categories their candidates are being marked
     * against.</p>
     */
    @GetMapping("/evaluation-categories/{prefix}/effective")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<List<EvaluationCategory>> getEffectiveEvaluationCategories(
            @PathVariable String prefix,
            @RequestParam(required = false) InterviewRound round) {
        return ResponseEntity.ok(categoryFormatter.getCategories(prefix, round));
    }

    /** The settings a round's interview runs on, template and defaults merged. */
    @GetMapping("/interview-template/{prefix}")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<EffectiveInterviewTemplate> getInterviewTemplate(
            @PathVariable String prefix,
            @RequestParam(required = false) InterviewRound round) {
        return ResponseEntity.ok(templateService.resolve(prefix, round));
    }

    /**
     * Sets a round's question budget and pitch.
     *
     * <p>A null number means "use the platform default", which is how a
     * recruiter clears an override rather than having to guess what the default
     * was and retype it.</p>
     */
    @PostMapping("/interview-template")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    public ResponseEntity<InterviewTemplate> saveInterviewTemplate(
            @RequestBody InterviewTemplateRequest request) {
        return ResponseEntity.ok(templateService.save(
                request.getJobPrefix(),
                request.getRound(),
                request.getMinQuestions(),
                request.getMaxQuestions(),
                request.getBaselineDifficulty(),
                request.isAdaptiveDifficulty()));
    }

    @PostMapping("/evaluation-categories")
    @PreAuthorize("hasAuthority('JOB_POST_CREATE')")
    @Transactional
    public ResponseEntity<List<EvaluationCategory>> saveEvaluationCategories(
            @RequestBody EvaluationCategorySaveRequest request) {

        String jobPrefix = request.getJobPrefix();
        InterviewRound round = request.getRound();

        // Scoped to the round being edited. Reading every row and deleting
        // whatever is not in this payload would wipe the other round's list
        // every time one of them was saved.
        List<EvaluationCategory> existing = round == null
                ? evaluationCategoryRepository.findAllByJobPrefixAndRoundIsNull(jobPrefix)
                : evaluationCategoryRepository.findAllByJobPrefixAndRound(jobPrefix, round);

        // Index existing categories by name for O(1) lookup
        Map<String, EvaluationCategory> existingByName = existing.stream()
                .collect(Collectors.toMap(EvaluationCategory::getCategoryName, c -> c));

        // Track incoming category names to identify removals
        Set<String> incomingNames = request.getCategories().stream()
                .map(EvaluationCategorySaveRequest.CategoryItem::getCategoryName)
                .collect(Collectors.toSet());

        // Update existing or create new
        List<EvaluationCategory> toSave = new ArrayList<>();
        for (var item : request.getCategories()) {
            EvaluationCategory cat = existingByName.get(item.getCategoryName());
            if (cat != null) {
                // Update in-place — preserves ID and createdAt
                cat.setWeight(item.getWeight());
                cat.setDescription(item.getDescription());
                toSave.add(cat);
            } else {
                // New category
                toSave.add(EvaluationCategory.builder()
                        .jobPrefix(jobPrefix)
                        .round(round)
                        .categoryName(item.getCategoryName())
                        .weight(item.getWeight())
                        .description(item.getDescription())
                        .build());
            }
        }

        // Delete categories that were removed by the admin
        List<EvaluationCategory> toDelete = existing.stream()
                .filter(c -> !incomingNames.contains(c.getCategoryName()))
                .toList();
        if (!toDelete.isEmpty()) {
            evaluationCategoryRepository.deleteAll(toDelete);
            evaluationCategoryRepository.flush();
        }

        List<EvaluationCategory> saved = evaluationCategoryRepository.saveAll(toSave);
        return ResponseEntity.ok(saved);
    }
}
