package com.rightpath.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.dto.JobPromptRequest;
import com.rightpath.entity.EvaluationCategory;
import com.rightpath.entity.InterviewTemplate;
import com.rightpath.entity.JobPrompt;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.EvaluationCategoryRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.JobPromptRepository;

/**
 * Reuses one job's AI set-up for another.
 *
 * <p>A new job has a new job id, and every prompt, evaluation category and interview setting is filed under
 * the old one's, so a recruiter opening a fresh job found nothing and had to retype what already existed. This
 * copies it across in one step, so assigning an assessment or an interview to the new job needs no rewriting.</p>
 */
@Service
public class JobPromptCopyService {

    private final JobPromptRepository promptRepository;
    private final JobPromptService promptService;
    private final EvaluationCategoryRepository categoryRepository;
    private final InterviewTemplateService templateService;
    private final JobPostRepository jobPostRepository;

    public JobPromptCopyService(JobPromptRepository promptRepository, JobPromptService promptService,
            EvaluationCategoryRepository categoryRepository, InterviewTemplateService templateService,
            JobPostRepository jobPostRepository) {
        this.promptRepository = promptRepository;
        this.promptService = promptService;
        this.categoryRepository = categoryRepository;
        this.templateService = templateService;
        this.jobPostRepository = jobPostRepository;
    }

    /** Every job that has at least one prompt, with the kinds it has, for a "reuse from" list. */
    public Map<String, Set<String>> jobsWithPrompts() {
        Map<String, Set<String>> byJob = new LinkedHashMap<>();
        for (Object[] row : promptRepository.findJobPromptTypes()) {
            byJob.computeIfAbsent((String) row[0], key -> new TreeSet<>()).add(String.valueOf(row[1]));
        }
        return byJob;
    }

    /**
     * Copy prompts, evaluation categories and interview settings from one job to another.
     *
     * <p>What the source has replaces what the target had of the same kind. Anything the target has that the
     * source does not (a prompt type only the target configured) is left alone.</p>
     */
    @Transactional
    public Map<String, Integer> copy(String fromJobPrefix, String toJobPrefix) {
        if (fromJobPrefix == null || toJobPrefix == null || fromJobPrefix.isBlank() || toJobPrefix.isBlank()) {
            throw new IllegalArgumentException("Both the job to copy from and the job to copy to are required");
        }
        if (fromJobPrefix.equals(toJobPrefix)) {
            throw new IllegalArgumentException("Choose a different job to copy from");
        }
        jobPostRepository.findByJobPrefix(toJobPrefix)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + toJobPrefix));

        List<JobPrompt> prompts = promptRepository.findAllByJobPrefix(fromJobPrefix);
        if (prompts.isEmpty()) {
            throw new ResourceNotFoundException("The job " + fromJobPrefix + " has no prompts to reuse");
        }
        for (JobPrompt prompt : prompts) {
            JobPromptRequest request = new JobPromptRequest();
            request.setJobPrefix(toJobPrefix);
            request.setPromptType(prompt.getPromptType());
            request.setPromptStage(prompt.getPromptStage());
            request.setPrompt(prompt.getPrompt());
            promptService.saveJobPrompt(request);
        }

        // Categories are copied a round at a time: the target's list for a round is replaced by the source's,
        // so the weights the source added up to still add up.
        List<EvaluationCategory> categories = categoryRepository.findAllByJobPrefix(fromJobPrefix);
        Map<Object, List<EvaluationCategory>> byRound = new LinkedHashMap<>();
        for (EvaluationCategory category : categories) {
            byRound.computeIfAbsent(category.getRound() == null ? "none" : category.getRound(), key -> new ArrayList<>())
                    .add(category);
        }
        int copiedCategories = 0;
        for (List<EvaluationCategory> group : byRound.values()) {
            var round = group.get(0).getRound();
            List<EvaluationCategory> existing = round == null
                    ? categoryRepository.findAllByJobPrefixAndRoundIsNull(toJobPrefix)
                    : categoryRepository.findAllByJobPrefixAndRound(toJobPrefix, round);
            categoryRepository.deleteAll(existing);
            categoryRepository.flush();
            for (EvaluationCategory source : group) {
                categoryRepository.save(EvaluationCategory.builder()
                        .jobPrefix(toJobPrefix)
                        .round(round)
                        .categoryName(source.getCategoryName())
                        .weight(source.getWeight())
                        .description(source.getDescription())
                        .build());
                copiedCategories++;
            }
        }

        int copiedTemplates = 0;
        for (InterviewTemplate template : templateService.findAll(fromJobPrefix)) {
            templateService.save(toJobPrefix, template.getRound(), template.getMinQuestions(),
                    template.getMaxQuestions(), template.getBaselineDifficulty(), template.isAdaptiveDifficulty());
            copiedTemplates++;
        }

        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("prompts", prompts.size());
        counts.put("categories", copiedCategories);
        counts.put("interviewSettings", copiedTemplates);
        return counts;
    }
}
