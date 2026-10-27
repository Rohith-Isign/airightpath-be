package com.rightpath.dto;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.multipart.MultipartFile;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssignAssessmentDto {
    private List<String> candidateEmails;
    private String uploadedBy;
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime startTime;
    @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm")
    private LocalDateTime deadline;
    private MultipartFile aptitudeQuestionPaper;
    private MultipartFile codingQuestionPaper;
    private MultipartFile aptitudeAnswerKey;  // Only for aptitude
    private boolean adminAcceptance = false;
    private String adminComments;
    private String jobPrefix;

    /**
     * Exam timing chosen by the admin, per assessment type. Only the
     * minutes-per-question values drive the exam clock; the counts and estimates
     * are what the admin was shown at assign time and are kept for reporting.
     * All are null when the type is not being assigned, or when an older client
     * omits them.
     */
    private Integer aptitudeMinutesPerQuestion;
    private Integer aptitudeQuestionCount;
    private Integer aptitudeEstimatedDurationMinutes;
    private Integer codingMinutesPerQuestion;
    private Integer codingQuestionCount;
    private Integer codingEstimatedDurationMinutes;

    /**
     * The percentage each paper must reach to pass. Null falls back to
     * {@link com.rightpath.entity.Assessment#DEFAULT_PASS_PERCENTAGE}, which is
     * what an older client that does not send these will get.
     */
    private Integer aptitudePassPercentage;
    private Integer codingPassPercentage;
}