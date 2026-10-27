package com.rightpath.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.rightpath.dto.CodingQuestion;
import com.rightpath.dto.Question;
import com.rightpath.enums.AssessmentType;
import com.rightpath.service.OpenAiService;
import com.rightpath.service.QuestionBankTracker;

@RestController
@RequestMapping("/api")
public class QuestionController {

    private static final Logger log = LoggerFactory.getLogger(QuestionController.class);
    private final OpenAiService openAiService;
    private final QuestionBankTracker questionBankTracker;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public QuestionController(OpenAiService openAiService, QuestionBankTracker questionBankTracker) {
        this.openAiService = openAiService;
        this.questionBankTracker = questionBankTracker;
    }

    /**
     * Keep what the generator produced, whether or not it is ever assigned.
     *
     * <p>Generated questions went straight back to the browser and were only stored if an admin
     * went on to assign them, so one generated and set aside was gone, and the bank could not show
     * "generated, never used". Bookkeeping only: a failure here must not lose the admin the questions
     * they waited minutes for.</p>
     */
    private void keep(String jobPrefix, AssessmentType type, Object questions) {
        try {
            String actor = org.springframework.security.core.context.SecurityContextHolder.getContext()
                    .getAuthentication() == null ? null
                            : org.springframework.security.core.context.SecurityContextHolder.getContext()
                                    .getAuthentication().getName();
            questionBankTracker.recordGenerated(jobPrefix, type, mapper.writeValueAsString(questions), actor);
        } catch (Exception e) {
            log.error("Could not keep the generated {} questions for {} in the question bank", type, jobPrefix, e);
        }
    }

    @GetMapping("/generate-questions")
    @PreAuthorize("hasAuthority('QUESTION_GENERATE')")
    public List<Question> generateQuestions(@RequestParam String jobPrefix) {
        log.info("Received request to generate aptitude questions for jobPrefix={}", jobPrefix);
        long start = System.currentTimeMillis();
        try {
            List<Question> questions = openAiService.generateQuestions(jobPrefix);
            log.info("Aptitude questions generated successfully for jobPrefix={}, count={}, took={}ms",
                    jobPrefix, questions.size(), System.currentTimeMillis() - start);
            keep(jobPrefix, AssessmentType.APTITUDE, questions);
            return questions;
        } catch (Exception e) {
            log.error("Failed to generate aptitude questions for jobPrefix={}, took={}ms, error={}",
                    jobPrefix, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }

    @GetMapping("/generate-coding-questions")
    @PreAuthorize("hasAuthority('CODING_QUESTION_GENERATE')")
    public List<CodingQuestion> generateCodingQuestions(@RequestParam String jobPrefix) {
        log.info("Received request to generate coding questions for jobPrefix={}", jobPrefix);
        long start = System.currentTimeMillis();
        try {
            List<CodingQuestion> questions = openAiService.generateCodingQuestions(jobPrefix);
            log.info("Coding questions generated successfully for jobPrefix={}, count={}, took={}ms",
                    jobPrefix, questions.size(), System.currentTimeMillis() - start);
            keep(jobPrefix, AssessmentType.CODING, questions);
            return questions;
        } catch (Exception e) {
            log.error("Failed to generate coding questions for jobPrefix={}, took={}ms, error={}",
                    jobPrefix, System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
    }
}
