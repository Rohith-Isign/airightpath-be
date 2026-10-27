package com.rightpath.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AssignInterviewBulkDTO {
    private List<String> emails;
    private String jobPrefix;
    private LocalDateTime assignedAt;
    private LocalDateTime deadlineTime;
    private boolean sendEmail = true;

    /**
     * Which interview to book. Null means the default round, so a caller that
     * predates rounds keeps booking technical interviews exactly as before.
     */
    private com.rightpath.enums.InterviewRound round;

    private LocalDate questionsFromDate;  // changed from LocalDateTime
    private LocalDate questionsToDate;
}
