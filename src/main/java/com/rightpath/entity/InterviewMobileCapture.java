package com.rightpath.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One still taken from the candidate's paired phone during an interview.
 *
 * <p>Its own table rather than more values for the proctoring capture type. The
 * type column there is a database ENUM, and {@code ddl-auto: update} adds
 * columns and tables but does not extend an existing ENUM — new values would be
 * rejected by every environment that already has the table, until someone ran
 * an ALTER by hand. A new table is created on its own, and {@link #kind} is a
 * plain string for the same reason.</p>
 */
@Entity
@Table(name = "interview_mobile_capture", indexes = {
        @Index(name = "idx_mobile_capture_interview", columnList = "interview_schedule_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterviewMobileCapture {

    /** The still taken when the phone's view of the room was approved, before the interview. */
    public static final String ROOM_PHOTO = "ROOM_PHOTO";

    /** A still taken every so often while the interview runs. */
    public static final String MONITOR_FRAME = "MONITOR_FRAME";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "interview_schedule_id", nullable = false)
    private Long interviewScheduleId;

    @Column(name = "candidate_email", nullable = false)
    private String candidateEmail;

    @Column(name = "kind", nullable = false, length = 24)
    private String kind;

    @Column(name = "frame_index", nullable = false)
    private int frameIndex;

    @Column(name = "container_name")
    private String containerName;

    @Column(name = "file_name", length = 512)
    private String fileName;

    @Column(name = "content_type", length = 64)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** UTC, like every other zone-less timestamp on the server. */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "uploaded_at")
    private LocalDateTime uploadedAt;
}
