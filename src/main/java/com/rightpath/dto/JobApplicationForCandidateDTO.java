package com.rightpath.dto;

import java.time.LocalDateTime;

import org.springframework.web.multipart.MultipartFile;

import com.drew.lang.annotations.NotNull;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rightpath.entity.JobApplicationForCandidate;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class JobApplicationForCandidateDTO {
	
	   private Long id;
	  
	  @NotBlank
	    private String email;  // We collect email to map to Users entity

	    @NotBlank
	    private String lastName;
	    @NotBlank
	    private String firstName;

	    @NotBlank
	    private String experience;

	    @NotBlank
	    private String address;

	    @NotBlank
	    private String jobRole;

	    @NotNull
	    @JsonIgnore
	    private MultipartFile resume;
	    
	    
	    private String resumeFileName;
	    private String contentType;
	    private String userEmail;
	    private String mobileNumber;

	    // Optional referral details — who referred this candidate. All nullable.
	    private String referralId;
	    private String referralName;
	    // Referral verification state (PENDING/VERIFIED/REJECTED); null when no referral.
	    private String referralStatus;

	    private String jobPrefix;
	    
	    private double matchPercent;
	    private String status;

	    // Human-readable label of the CURRENT pipeline stage (derived from status),
	    // so the UI can highlight which column is active. e.g. "Exam Completed".
	    private String currentStage;
	    // Separate ATS resume-scan status and shortlisting status columns.
	    private String atsScanStatus;
	    private String shortlistStatus;

	    private String confirmationStatus;
	    private String acknowledgedStatus;
	    private String reconfirmationStatus;
	    private String examLinkStatus;
	    private String examCompletedStatus;
	    private String rejectionStatus;
	    private String writtenTestStatus;
	    private String interview;
	    private String interviewStatus;
	    private String jobTitle;
	    private String companyName;
	    private String applicationDeadline;

	    /**
	     * When the candidate submitted this application, set once on persist.
	     *
	     * Exposed so an admin reading the pipeline can tell a application filed
	     * this morning from one that has been sitting untouched for three weeks —
	     * the status column alone says nothing about age.
	     */
	    private LocalDateTime createdAt;

	    
	    public JobApplicationForCandidateDTO(JobApplicationForCandidate entity) {
	        this.id = entity.getId();
	        this.firstName = entity.getFirstName();
	        this.lastName = entity.getLastName();
	        this.experience = entity.getExperience();
	        this.address = entity.getAddress();
	        this.jobRole = entity.getJobRole();
	        this.resumeFileName = entity.getResumeFileName();
	        this.contentType = entity.getContentType();
	        this.userEmail = entity.getUser().getEmail();
	        this.referralId = entity.getReferralId();
	        this.referralName = entity.getReferralName();
	        this.referralStatus = entity.getReferralStatus() != null ? entity.getReferralStatus().name() : null;
	        this.jobPrefix = entity.getJobPost().getJobPrefix();
	        this.status = entity.getStatus() != null ? entity.getStatus().name() : null;
	        this.confirmationStatus = entity.getConfirmationStatus();
	        this.acknowledgedStatus = entity.getAcknowledgedStatus();
	        this.reconfirmationStatus = entity.getReconfirmationStatus();
	        this.examLinkStatus = entity.getExamLinkStatus();
	        this.examCompletedStatus = entity.getExamCompletedStatus();
	        this.rejectionStatus = entity.getRejectionStatus();
	        this.writtenTestStatus = entity.getWrittenTestStatus();
	        this.interview = entity.getInterview();
	        this.createdAt = entity.getCreatedAt();
	        this.currentStage = humanizeStage(this.status);
	        this.atsScanStatus = deriveAtsScanStatus(entity.getAtsScanStatus(), this.status);
	        this.shortlistStatus = deriveShortlistStatus(entity.getShortlistStatus(), this.status);
	    }

	    /** Turns an enum name like {@code EXAM_COMPLETED} into {@code "Exam Completed"}. */
	    public static String humanizeStage(String statusName) {
	        if (statusName == null || statusName.isBlank()) {
	            return null;
	        }
	        String[] words = statusName.toLowerCase().split("_");
	        StringBuilder sb = new StringBuilder();
	        for (String w : words) {
	            if (w.isEmpty()) {
	                continue;
	            }
	            if (sb.length() > 0) {
	                sb.append(' ');
	            }
	            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
	        }
	        return sb.toString();
	    }

	    /**
	     * ATS scan status: the stored value if present, otherwise derived — anything
	     * past APPLIED has necessarily been screened.
	     */
	    public static String deriveAtsScanStatus(String stored, String statusName) {
	        if (stored != null && !stored.isBlank()) {
	            return stored;
	        }
	        if (statusName == null || "APPLIED".equalsIgnoreCase(statusName)) {
	            return "Pending";
	        }
	        return "Screening Completed";
	    }

	    /**
	     * Shortlist status: the stored value if present, otherwise derived — any stage
	     * beyond APPLIED (other than REJECTED, which is ambiguous) implies shortlisted.
	     */
	    public static String deriveShortlistStatus(String stored, String statusName) {
	        if (stored != null && !stored.isBlank()) {
	            return stored;
	        }
	        if (statusName == null || "APPLIED".equalsIgnoreCase(statusName)) {
	            return "Pending";
	        }
	        if ("REJECTED".equalsIgnoreCase(statusName)) {
	            return null;
	        }
	        return "Shortlisted";
	    }

}