package com.rightpath.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The new window for a booked interview that has not been started. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RescheduleInterviewDTO {
    private LocalDateTime assignedAt;
    private LocalDateTime deadlineTime;
    /** Defaults to true: the candidate was told the old window. */
    private boolean notify = true;
}
