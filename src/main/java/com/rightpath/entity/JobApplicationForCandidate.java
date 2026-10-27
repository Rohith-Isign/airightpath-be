package com.rightpath.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.rightpath.enums.ApplicationStatus;
import com.rightpath.enums.ReferralStatus;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class JobApplicationForCandidate {
	
	 @Id
	    @GeneratedValue(strategy = GenerationType.IDENTITY)
	    private Long id;

	
	    private String lastName;
	   
	    private String firstName;
	    private String experience;
	    private String address;
	    private String jobRole;
	    private String resumeFileName;
	    private String mobileNumber;

	    // Optional referral details captured at apply time. Both nullable: a candidate
	    // may apply with neither, or supply who referred them (id and/or name).
	    private String referralId;
	    private String referralName;

	    // Verification state of the referral. Defaults to PENDING at apply time when
	    // referral details are supplied; null when there is no referral. Updated later
	    // by a recruiter (VERIFIED / REJECTED).
	    @Enumerated(EnumType.STRING)
	    private ReferralStatus referralStatus;
	    

	    @Lob
	    private byte[] resumeData;
	    private double matchPercent; 
	    private String contentType;
	     	

	    @ManyToOne(fetch = FetchType.LAZY)
	    @JoinColumn(name = "user_email", referencedColumnName = "email")
	    private Users user; 
	    
	    
	    @ManyToOne(fetch = FetchType.LAZY)
	    @JoinColumn(name = "jobPrefix")
	    private JobPost jobPost;
	    
	    @Enumerated(EnumType.STRING)
	    private ApplicationStatus status;

	    // ATS resume-scan outcome (e.g. "Screening Completed") — independent of shortlisting.
	    private String atsScanStatus;
	    // Shortlisting outcome after ATS screening (e.g. "Shortlisted" / "Not Shortlisted").
	    private String shortlistStatus;

	    private String confirmationStatus;
	    private String acknowledgedStatus;
	    private String reconfirmationStatus;
	    private String examLinkStatus;
	    private String examCompletedStatus;
	    private String rejectionStatus;
	    
	    private LocalDate examDate;
	    private LocalTime examTime;
	    private String writtenTestStatus;
	    private String interview;

	    private LocalDateTime createdAt;
	    private LocalDateTime updatedAt;

	    @PrePersist
	    protected void onCreate() {
	        this.createdAt = LocalDateTime.now();
	        this.updatedAt = LocalDateTime.now();
	    }

	    @PreUpdate
	    protected void onUpdate() {
	        this.updatedAt = LocalDateTime.now();
	    }
}