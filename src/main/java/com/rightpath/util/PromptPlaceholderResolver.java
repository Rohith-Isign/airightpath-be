package com.rightpath.util;

import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.rightpath.entity.JobPost;
import com.rightpath.enums.InterviewRound;
import com.rightpath.repository.JobPostRepository;

@Component
public class PromptPlaceholderResolver {

    private static final Logger log = LoggerFactory.getLogger(PromptPlaceholderResolver.class);

    private final JobPostRepository jobPostRepository;
    private final EvaluationCategoryFormatter categoryFormatter;

    public PromptPlaceholderResolver(JobPostRepository jobPostRepository,
                                     EvaluationCategoryFormatter categoryFormatter) {
        this.jobPostRepository = jobPostRepository;
        this.categoryFormatter = categoryFormatter;
    }

    /**
     * Resolve job-level placeholders for APTITUDE, CODING, and INTERVIEW/SUMMARY prompts.
     */
    public String resolveJobPlaceholders(String template, String jobPrefix) {
        if (template == null || template.isBlank()) {
            return template;
        }

        Optional<JobPost> jobPostOpt = jobPostRepository.findByJobPrefix(jobPrefix);
        if (jobPostOpt.isEmpty()) {
            log.warn("JobPost not found for prefix '{}', returning template with unresolved placeholders", jobPrefix);
            return template;
        }

        JobPost job = jobPostOpt.get();

        return template
                .replace("{{jobPrefix}}", safe(jobPrefix))
                .replace("{{jobTitle}}", safe(job.getJobTitle()))
                .replace("{{skills}}", safe(job.getKeySkills()))
                .replace("{{experience}}", safe(job.getExperience()))
                .replace("{{education}}", safe(job.getEducation()))
                .replace("{{jobDescription}}", safe(job.getJobDescription()))
                .replace("{{companyName}}", safe(job.getCompanyName()))
                .replace("{{location}}", safe(job.getLocation()))
                .replace("{{role}}", safe(job.getRole()))
                .replace("{{department}}", safe(job.getDepartment()));
    }

    /**
     * Resolve all placeholders for INTERVIEW/START prompts (job + interview-specific).
     */
    private static final Pattern YOUR_NAME_PATTERN = Pattern.compile(
            "\\[\\s*[Yy]our\\s+[Nn]ame\\s*\\]");

    public String resolveAllInterviewPlaceholders(String template, String jobPrefix, String email,
                                                    String interviewerName, InterviewRound round) {
        String resolved = resolveJobPlaceholders(template, jobPrefix);
        String safeName = safe(interviewerName);
        resolved = resolved
                .replace("{{email}}", safe(email))
                .replace("{{interviewerName}}", safeName)
                .replace("{{categories}}", categoryFormatter.buildStartPromptCategorySection(jobPrefix, round));
        // Handle all variations: [Your Name], [your name], [Your  Name], [ Your Name ], etc.
        resolved = YOUR_NAME_PATTERN.matcher(resolved).replaceAll(safeName);
        return resolved;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
