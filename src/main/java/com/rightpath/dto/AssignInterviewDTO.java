package com.rightpath.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AssignInterviewDTO {
    private String jobPrefix;
    private String email;
    private LocalDateTime assignedAt;
    private LocalDateTime deadlineTime;
    private LocalDate questionsFromDate;
    private LocalDate questionsToDate;
}
