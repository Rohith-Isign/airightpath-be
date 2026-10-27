package com.rightpath.service.impl;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.rightpath.dto.JobApplicationForCandidateDTO;
import com.rightpath.dto.ScreeningResponseDTO;
import com.rightpath.dto.ScreeningResultDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.ApplicationStatus;
import com.rightpath.enums.EmailType;
import com.rightpath.enums.ReferralStatus;
import com.rightpath.exceptions.ApplicationDeadlinePassedException;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.JobApplicationForCandidateService;
import com.rightpath.service.ResumeService;
import com.rightpath.service.WhatsAppService;
import com.rightpath.util.BusinessSchedule;
import com.rightpath.util.StatusTransitionValidator;
import com.rightpath.util.SynonymLoader;

import jakarta.transaction.Transactional;



@Service
public class JobApplicationForCandidateServiceImpl implements JobApplicationForCandidateService {

    @Autowired
    private JobApplicationForCandidateRepository applicationForCandidateRepository;

    @Autowired
    private UsersRepository usersRepository;
    
    @Autowired
    private ResumeService resumeService;
    
    @Autowired
    private final JobPostRepository jobPostRepository;
    
    @Autowired
    private SynonymLoader synonymLoader;
    
    @Autowired
    private EmailServiceImpl emailService;

    @Autowired
    private WhatsAppService whatsAppService;

    @Autowired
    private AssessmentRepository assessmentRepository;

    /** Owns the business timezone: "now" for validation, and display formatting. */
    @Autowired
    private BusinessSchedule businessSchedule;

    private static final Logger logger = LoggerFactory.getLogger(JobApplicationForCandidateServiceImpl.class);


    private final String baseUrl;

    @Value("${ats.screening.threshold:60.0}")
    private double atsThreshold;



    @Autowired
    private JavaMailSender mailSender;

    public JobApplicationForCandidateServiceImpl(JobApplicationForCandidateRepository applicationForCandidateRepository,
			UsersRepository usersRepository, JobPostRepository jobPostRepository,@Value("${app.base.url}")  String baseUrl) {
		super();
		this.applicationForCandidateRepository = applicationForCandidateRepository;
		this.usersRepository = usersRepository;
		this.jobPostRepository = jobPostRepository;
		this.baseUrl=baseUrl;
	}

    
    private String getLogoHtml() {
        return """
            <div style='text-align: center; margin-bottom: 20px;'>
                <img src='cid:isigntech-logo' alt='Company Logo' style='height: 60px;'/>
            </div>
            """;
    }
    
    @Override
    public void applyForJob(JobApplicationForCandidateDTO dto) {
        Users user = usersRepository.findById(dto.getEmail())
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + dto.getEmail()));

        // An archived posting reads as gone to candidates: the public apply link must
        // answer with a clean 404, not let someone apply to a deleted job.
        JobPost jobPost = jobPostRepository.findByJobPrefix(dto.getJobPrefix())
                .filter(post -> post.getDeletedAt() == null)
                .orElseThrow(() -> new JobPostNotFoundException(dto.getJobPrefix()));

        // Business date, so this agrees with the ACTIVE/EXPIRED buckets the job
        // listing shows; on a UTC server LocalDate.now() would close applications
        // for a job the candidate can still see as active (and vice versa).
        if (jobPost.getApplicationDeadline() != null
                && businessSchedule.today().isAfter(jobPost.getApplicationDeadline())) {
            throw new ApplicationDeadlinePassedException(
                    "Application deadline for " + jobPost.getJobTitle() + " has passed");
        }

        String jobTitle = jobPost.getJobTitle();

        JobApplicationForCandidate application = JobApplicationForCandidate.builder()
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .experience(dto.getExperience())
                .address(dto.getAddress())
                .jobRole(dto.getJobRole())
                .mobileNumber(dto.getMobileNumber())
                .referralId(dto.getReferralId())
                .referralName(dto.getReferralName())
                .user(user)
                .jobPost(jobPost) // set relationship with JobPost
                .build();

        // Default referral status to PENDING only when the candidate actually
        // supplied referral details; a non-referred application has no referral status.
        boolean hasReferral = (dto.getReferralId() != null && !dto.getReferralId().isBlank())
                || (dto.getReferralName() != null && !dto.getReferralName().isBlank());
        if (hasReferral) {
            application.setReferralStatus(ReferralStatus.PENDING);
        }

        try {
            if (dto.getResume() != null && !dto.getResume().isEmpty()) {
                application.setResumeFileName(dto.getResume().getOriginalFilename());
                application.setResumeData(dto.getResume().getBytes());
                application.setContentType(dto.getResume().getContentType());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error reading resume file", e);
        }

        // Centralized status logic: REF-000 → SHORTLISTED, others → APPLIED
        if ("REF-000".equalsIgnoreCase(dto.getJobPrefix())) {
            application.setStatus(ApplicationStatus.SHORTLISTED);
        } else {
            application.setStatus(ApplicationStatus.APPLIED);
        }

        applicationForCandidateRepository.save(application);

        // Prepare params for universal email
        Map<String, Object> emailParams = new HashMap<>();
        emailParams.put("recipientEmail", dto.getEmail());
        emailParams.put("firstName", dto.getFirstName());
        emailParams.put("lastName", dto.getLastName());
        emailParams.put("jobTitle", jobTitle);
        emailParams.put("jobPrefix", dto.getJobPrefix());
        emailParams.put("mobileNumber", dto.getMobileNumber());

        emailService.sendUniversalEmail(EmailType.APPLICATION_SUCCESS, emailParams);

        whatsAppService.sendWhatsAppMessage(
                dto.getMobileNumber(),
                WhatsAppService.MessageType.JOB_APPLIED,
                dto.getFirstName(),
                dto.getLastName(),
                dto.getJobPrefix(),
                jobTitle
        );
    }





    
    @Override
    public List<JobApplicationForCandidateDTO> getAllApplications() {
        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findAll();
        return applications.stream().map(this::convertToDTO).collect(Collectors.toList());
    }

    @Override
    public List<JobApplicationForCandidateDTO> getApplicationsByEmail(String email) {
        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByUserEmail(email);
        return applications.stream().map(this::convertToDTO).collect(Collectors.toList());
    }



  
   @Transactional
public void updateJobApplicationByJobPrefixAndEmail(JobApplicationForCandidateDTO dto) {
    List<JobApplicationForCandidate> applications = 
        applicationForCandidateRepository.findByJobPrefixAndEmail(dto.getJobPrefix(), dto.getEmail());

    if (applications.isEmpty()) {
        throw new ResourceNotFoundException("No application found for the given jobPrefix and email.");
    }

    JobApplicationForCandidate application = applications.get(0);

    // ✅ Update only if not null
    if (dto.getFirstName() != null) application.setFirstName(dto.getFirstName());
    if (dto.getLastName() != null) application.setLastName(dto.getLastName());
    if (dto.getExperience() != null) application.setExperience(dto.getExperience());
    if (dto.getAddress() != null) application.setAddress(dto.getAddress());
    if (dto.getJobRole() != null) application.setJobRole(dto.getJobRole());
    if (dto.getMobileNumber() != null) application.setMobileNumber(dto.getMobileNumber());
    
    
    // ✅ Update resume if new file is uploaded
    if (dto.getResume() != null && !dto.getResume().isEmpty()) {
        application.setResumeFileName(dto.getResume().getOriginalFilename());
        application.setContentType(dto.getResume().getContentType());
        try {
            application.setResumeData(dto.getResume().getBytes());
        } catch (IOException e) {
            throw new RuntimeException("Resume processing failed", e);
        }
    }

    // ✅ Ensure save is called
    applicationForCandidateRepository.save(application);
}

 
    
    private JobApplicationForCandidateDTO convertToDTO(JobApplicationForCandidate app) {
        JobApplicationForCandidateDTO dto = new JobApplicationForCandidateDTO();
        dto.setId(app.getId());
        dto.setFirstName(app.getFirstName());
        dto.setLastName(app.getLastName());
        dto.setExperience(app.getExperience());
        dto.setAddress(app.getAddress());
        dto.setJobRole(app.getJobRole());
        dto.setResumeFileName(app.getResumeFileName());
        dto.setContentType(app.getContentType());
        dto.setStatus(app.getStatus() != null ? app.getStatus().name() : null);
        // Score from the last screening run. Read paths surface it without re-scoring;
        // a screening run overwrites it with the freshly computed value.
        dto.setMatchPercent(app.getMatchPercent());
        dto.setConfirmationStatus(app.getConfirmationStatus());
        dto.setAcknowledgedStatus(app.getAcknowledgedStatus());
        dto.setReconfirmationStatus(app.getReconfirmationStatus());
        dto.setExamLinkStatus(app.getExamLinkStatus());
        dto.setExamCompletedStatus(app.getExamCompletedStatus());
        dto.setUserEmail(app.getUser().getEmail());
        dto.setEmail(app.getUser().getEmail());
        dto.setMobileNumber(app.getMobileNumber());
        dto.setReferralId(app.getReferralId());
        dto.setReferralName(app.getReferralName());
        dto.setReferralStatus(app.getReferralStatus() != null ? app.getReferralStatus().name() : null);
        dto.setRejectionStatus(app.getRejectionStatus());
        dto.setWrittenTestStatus(app.getWrittenTestStatus());
        dto.setInterview(app.getInterview());
        // When the candidate applied. Also set by the DTO's entity constructor,
        // but this mapper is what every admin read path goes through.
        dto.setCreatedAt(app.getCreatedAt());
        dto.setCurrentStage(JobApplicationForCandidateDTO.humanizeStage(dto.getStatus()));
        dto.setAtsScanStatus(JobApplicationForCandidateDTO.deriveAtsScanStatus(app.getAtsScanStatus(), dto.getStatus()));
        dto.setShortlistStatus(JobApplicationForCandidateDTO.deriveShortlistStatus(app.getShortlistStatus(), dto.getStatus()));
        if (app.getJobPost() != null) {
            dto.setJobPrefix(app.getJobPost().getJobPrefix());
            dto.setJobTitle(app.getJobPost().getJobTitle());
            dto.setCompanyName(app.getJobPost().getCompanyName());
            dto.setApplicationDeadline(app.getJobPost().getApplicationDeadline() != null
                    ? app.getJobPost().getApplicationDeadline().toString() : null);
        } else {
            dto.setJobPrefix(null);
        }

        return dto;
    }
    

    @Override
    public List<JobApplicationForCandidateDTO> getApplicationsByJobPrefix(String jobPrefix) {
        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPrefix(jobPrefix);
        return applications.stream().map(this::convertToDTO).collect(Collectors.toList());
    }
    
    
    @Override
    public List<JobApplicationForCandidateDTO> getApplicantsByJobPostId(Long jobPostId) {
        JobPost jobPost = jobPostRepository.findById(jobPostId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobPostId));

        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPost(jobPost);

        return applications.stream()
                .map(app -> {
                    JobApplicationForCandidateDTO dto = new JobApplicationForCandidateDTO();
                    dto.setId(app.getId());
                    dto.setUserEmail(app.getUser().getEmail());
                    dto.setJobPrefix(app.getJobPost().getJobPrefix());
                    return dto;
                })
                .collect(Collectors.toList());
    }
    
    
    /**
     * CORE LOGIC: Centralized Filtering Logic
     */
    private double calculateMatchPercent(JobApplicationForCandidate app, String[] requiredSkills, Map<String, List<String>> synonyms) {
        String resumeText = resumeService.extractText(app.getResumeData()).toLowerCase();

        long matchCount = Arrays.stream(requiredSkills)
                .filter(skill -> {
                    List<String> variations = synonyms.getOrDefault(skill, List.of(skill));
                    return variations.stream().anyMatch(resumeText::contains);
                })
                .count();

        return (matchCount * 100.0) / requiredSkills.length;
    }
    
    
    
    /**
     * MAIN METHOD TO PROCESS ALL CANDIDATES
     *
     * @deprecated screens the entire job on every call; use
     *             {@link #screenCandidates(String, java.util.List)} instead.
     */
    @Override
    @Deprecated(since = "2026-08")
    @Transactional
    public List<JobApplicationForCandidateDTO> filterCandidatesByPrefix(String jobPrefix) {
        JobPost jobPost = requireJobPost(jobPrefix);
        String[] requiredSkills = requiredSkills(jobPost);
        Map<String, List<String>> synonyms = synonymLoader.getSynonymMap();

        List<JobApplicationForCandidateDTO> processedList = new ArrayList<>();

        for (JobApplicationForCandidate app : applicationForCandidateRepository.findByJobPost(jobPost)) {
            ScreeningResultDTO result = screenOne(app, requiredSkills, synonyms);

            JobApplicationForCandidateDTO dto = convertToDTO(app);
            // Every row is scored even when the decision is not persisted, so this
            // response keeps showing a live match% for candidates further along.
            dto.setMatchPercent(result.matchPercent());
            processedList.add(dto);
        }
        return processedList;
    }

    @Override
    @Transactional
    public ScreeningResponseDTO screenCandidates(String jobPrefix, List<String> emails) {
        JobPost jobPost = requireJobPost(jobPrefix);
        String[] requiredSkills = requiredSkills(jobPost);
        Map<String, List<String>> synonyms = synonymLoader.getSynonymMap();

        List<JobApplicationForCandidate> applicants = applicationForCandidateRepository.findByJobPost(jobPost);
        boolean wholeJob = (emails == null || emails.isEmpty());
        List<ScreeningResultDTO> results = new ArrayList<>();

        if (wholeJob) {
            for (JobApplicationForCandidate app : applicants) {
                results.add(screenOne(app, requiredSkills, synonyms));
            }
        } else {
            Map<String, JobApplicationForCandidate> byEmail = new HashMap<>();
            for (JobApplicationForCandidate app : applicants) {
                if (app.getUser() != null && app.getUser().getEmail() != null) {
                    byEmail.putIfAbsent(normalizeEmail(app.getUser().getEmail()), app);
                }
            }

            // Walk the caller's list, not the applicant list: the response comes back
            // in the order the rows were selected, and an email with no application
            // is reported rather than silently dropped.
            Set<String> alreadyRequested = new LinkedHashSet<>();
            for (String email : emails) {
                if (email == null || email.isBlank()) {
                    continue;
                }
                if (!alreadyRequested.add(normalizeEmail(email))) {
                    continue;
                }
                JobApplicationForCandidate app = byEmail.get(normalizeEmail(email));
                results.add(app == null
                        ? ScreeningResultDTO.notFound(email.trim())
                        : screenOne(app, requiredSkills, synonyms));
            }
        }

        ScreeningResponseDTO response = ScreeningResponseDTO.of(jobPrefix, atsThreshold,
                wholeJob ? ScreeningResponseDTO.SCOPE_ALL : ScreeningResponseDTO.SCOPE_SELECTED, results);
        logger.info("ATS screening on {} ({}): {}", jobPrefix, response.scope(), response.message());
        return response;
    }

    /**
     * Screens one application: always scores it, but only persists the shortlist
     * decision when the row is still in the screening phase.
     *
     * <p>The score is computed even for a skipped row so the caller can show what the
     * candidate <em>would</em> have scored without that number changing anything.</p>
     */
    private ScreeningResultDTO screenOne(JobApplicationForCandidate app, String[] requiredSkills,
                                         Map<String, List<String>> synonyms) {
        String email = app.getUser() != null ? app.getUser().getEmail() : null;
        String fullName = (trimToEmpty(app.getFirstName()) + " " + trimToEmpty(app.getLastName())).trim();
        double matchPercent = calculateMatchPercent(app, requiredSkills, synonyms);

        String blockReason = screeningBlockReason(app);
        if (blockReason != null) {
            return ScreeningResultDTO.skipped(email, fullName, matchPercent,
                    app.getStatus() != null ? app.getStatus().name() : null, blockReason);
        }

        // Recompute and persist the shortlist decision on EVERY run so the status
        // matches the current score (e.g. after a resume edit + re-screen).
        boolean shortlisted = matchPercent >= atsThreshold;
        // Set directly rather than via StatusTransitionValidator: a re-screen is an
        // in-phase re-evaluation (REJECTED->SHORTLISTED, or same->same), which the
        // forward-only pipeline validator intentionally forbids.
        app.setStatus(shortlisted ? ApplicationStatus.SHORTLISTED : ApplicationStatus.REJECTED);
        app.setMatchPercent(matchPercent);
        // Record the ATS scan and shortlist outcomes in their own columns.
        app.setAtsScanStatus("Screening Completed");
        app.setShortlistStatus(shortlisted ? "Shortlisted" : "Not Shortlisted");
        applicationForCandidateRepository.save(app);

        return ScreeningResultDTO.screened(email, fullName, matchPercent, app.getStatus().name());
    }

    /**
     * Why ATS screening must leave this application alone, or null if it may be
     * (re)evaluated on this run.
     *
     * <p>Screenable is: APPLIED (the first screen), or a SHORTLISTED/REJECTED row that
     * ATS itself produced (atsScanStatus = "Screening Completed") and that was not
     * subsequently closed by a finalised rejection. Such rows may be re-screened —
     * e.g. after a resume edit — so the decision tracks the new score.</p>
     *
     * <p>Everything else is left untouched: candidates who have progressed past
     * shortlisting (ACKNOWLEDGED onward) must not be reverted, referral
     * auto-shortlists (never ATS-screened) keep their status, and manually or
     * test-rejected candidates are not resurrected behind a recruiter's back.</p>
     */
    private String screeningBlockReason(JobApplicationForCandidate app) {
        ApplicationStatus status = app.getStatus();

        if (status == ApplicationStatus.APPLIED) {
            return null;
        }
        if (status == null) {
            return "Application has no status; screening skipped.";
        }
        if (status != ApplicationStatus.SHORTLISTED && status != ApplicationStatus.REJECTED) {
            return "Candidate has progressed to " + JobApplicationForCandidateDTO.humanizeStage(status.name())
                    + "; re-screening would undo pipeline progress.";
        }
        if (!"Screening Completed".equals(app.getAtsScanStatus())) {
            return "Shortlisted without ATS screening (e.g. referral); left as-is.";
        }
        if (app.getRejectionStatus() != null && !app.getRejectionStatus().isBlank()) {
            return "Rejection is final (" + app.getRejectionStatus()
                    + "); re-screening cannot reopen it. Use manual shortlist with override.";
        }
        return null;
    }

    /**
     * Reads the shortlisted candidates. Read-only by design: listing a job's
     * shortlist must not re-score anyone, or merely opening the tab would rewrite
     * every applicant's status. Run {@link #screenCandidates(String, List)} to screen.
     */
    @Override
    public List<JobApplicationForCandidateDTO> getShortlistedCandidatesByPrefix(String jobPrefix) {
        return applicationsWithStatus(jobPrefix, ApplicationStatus.SHORTLISTED);
    }

    /**
     * Reads the rejected candidates. Read-only, for the same reason as
     * {@link #getShortlistedCandidatesByPrefix(String)}.
     */
    @Override
    public List<JobApplicationForCandidateDTO> getRejectedCandidatesByPrefix(String jobPrefix) {
        return applicationsWithStatus(jobPrefix, ApplicationStatus.REJECTED);
    }

    private List<JobApplicationForCandidateDTO> applicationsWithStatus(String jobPrefix, ApplicationStatus status) {
        return applicationForCandidateRepository.findByJobPost(requireJobPost(jobPrefix)).stream()
                .filter(app -> app.getStatus() == status)
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private JobPost requireJobPost(String jobPrefix) {
        return jobPostRepository.findByJobPrefix(jobPrefix)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with prefix: " + jobPrefix));
    }

    /**
     * The job's key skills, as the lowercase tokens screening scores against.
     * A job with none cannot be screened — say so plainly instead of failing with a
     * NullPointerException halfway through a batch.
     */
    private static String[] requiredSkills(JobPost jobPost) {
        String keySkills = jobPost.getKeySkills();
        if (keySkills == null || keySkills.isBlank()) {
            throw new IllegalStateException("Job " + jobPost.getJobPrefix()
                    + " has no key skills configured, so ATS screening cannot score candidates.");
        }
        return keySkills.toLowerCase().split(",\\s*");
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Email of the authenticated recruiter, as set by the JWT filter. Falls back to
     * "system" for non-request callers so an override is never logged without an actor.
     */
    private static String actingUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return (authentication == null || authentication.getName() == null) ? "system" : authentication.getName();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    public JobApplicationForCandidateDTO getApplicationByJobPrefixAndEmail(String jobPrefix, String email) {
        List<JobApplicationForCandidate> apps = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);

        if (apps.isEmpty()) throw new ResourceNotFoundException("No application found");

        return convertToDTO(apps.get(0));
    }

    @Override
    public JobApplicationForCandidateDTO updateReferralStatus(String jobPrefix, String email, String referralStatus) {
        List<JobApplicationForCandidate> apps = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);
        if (apps.isEmpty()) {
            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
        }

        ReferralStatus newStatus;
        try {
            newStatus = ReferralStatus.valueOf(referralStatus.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "Invalid referralStatus '" + referralStatus + "'. Allowed values: "
                            + Arrays.toString(ReferralStatus.values()));
        }

        JobApplicationForCandidate application = apps.get(0);
        application.setReferralStatus(newStatus);
        applicationForCandidateRepository.save(application);

        return convertToDTO(application);
    }

    @Override
    @Transactional
    public void shortlistCandidateWithoutAts(String jobPrefix, String email) {
        shortlistCandidateWithoutAts(jobPrefix, email, false);
    }

    @Override
    @Transactional
    public void shortlistCandidateWithoutAts(String jobPrefix, String email, boolean override) {
        List<JobApplicationForCandidate> applications =
                applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);
        if (applications.isEmpty()) {
            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
        }

        JobApplicationForCandidate application = applications.get(0);

        // "Shortlist Anyway": the recruiter is deliberately reopening a closed
        // application. REJECTED is terminal in the forward-only pipeline, so the
        // validator is bypassed for this one move rather than relaxed — every other
        // transition, including from SELECTED or mid-pipeline, still goes through it.
        boolean reopeningRejection = override && application.getStatus() == ApplicationStatus.REJECTED;

        if (reopeningRejection) {
            logger.info("Rejection overridden for {} on job {} by {}: REJECTED -> SHORTLISTED (was '{}')",
                    email, jobPrefix, actingUser(), application.getRejectionStatus());
            // Clearing the finalised-rejection marker returns the row to the ATS
            // screening phase, so a later re-screen can evaluate it again; leaving it
            // set would shortlist the candidate but lock them out of screening.
            application.setRejectionStatus(null);
        } else {
            // Manual shortlist bypasses ATS but still respects the workflow: only an
            // APPLIED application may move to SHORTLISTED. Anything else (already
            // shortlisted, rejected, or further along) throws and is reported as failed.
            StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.SHORTLISTED);
        }

        application.setStatus(ApplicationStatus.SHORTLISTED);
        application.setShortlistStatus(reopeningRejection ? "Shortlisted (Override)" : "Shortlisted");
        applicationForCandidateRepository.save(application);

        // Notify the candidate (email + WhatsApp), same as ATS shortlisting.
        // Best-effort: a notification failure must not fail an already-committed shortlist.
        try {
            Map<String, Object> emailParams = new HashMap<>();
            emailParams.put("recipientEmail", email);
            emailParams.put("firstName", application.getFirstName());
            emailParams.put("lastName", application.getLastName());
            emailParams.put("mobileNumber", application.getMobileNumber());
            // Named so the candidate reads which role they were shortlisted for,
            // rather than "the role you applied for".
            emailParams.put("jobPrefix", jobPrefix);
            if (application.getJobPost() != null) {
                emailParams.put("jobTitle", application.getJobPost().getJobTitle());
            }
            emailService.sendUniversalEmail(EmailType.SHORTLIST_NOTIFICATION, emailParams);
        } catch (Exception e) {
            logger.warn("Shortlist notification failed for {} (job {}): {}", email, jobPrefix, e.getMessage());
        }
    }

     
	    public List<JobApplicationForCandidateDTO> filterCandidates(Long jobPostId) {
	        JobPost jobPost = jobPostRepository.findById(jobPostId)
	                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));
	
	        String[] requiredSkills = jobPost.getKeySkills().toLowerCase().split(",\\s*");
	
	        List<JobApplicationForCandidate> applicants = applicationForCandidateRepository.findByJobPost(jobPost);
	
	        Map<String, List<String>> synonyms = synonymLoader.getSynonymMap();
	
	        return applicants.stream()
	                .filter(app -> {
	                    String resumeText = resumeService.extractText(app.getResumeData()).toLowerCase();
	
	                    long matchCount = Arrays.stream(requiredSkills)
	                            .filter(skill -> {
	                                List<String> variations = synonyms.getOrDefault(skill, List.of(skill));
	                                return variations.stream().anyMatch(resumeText::contains);
	                            })
	                            .count();
	
	                    double matchPercent = (matchCount * 100.0) / requiredSkills.length;
	                    System.out.println("Resume Text: " + resumeText);
	                    System.out.println("Required Skills: " + Arrays.toString(requiredSkills));
	                    System.out.println("Match %: " + matchPercent);
	                    return matchPercent >= atsThreshold;
	                })
	                .map(this::convertToDTO)
	                .collect(Collectors.toList());
	    }

    
    
    
	    @Override
	    public JobApplicationForCandidate getById(Long id) {
	        return applicationForCandidateRepository.findById(id)
	                         .orElseThrow(() -> new ResourceNotFoundException("Candidate not found with id: " + id));
	    }
    
	    @Override
	    public void sendAcknowledgementMailAndUpdateStatus(String jobPrefix, String email, LocalDateTime examSlot) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository
	                .findByJobPrefixAndEmail(jobPrefix, email);
	        JobPost jobs = jobPostRepository.findByJobPrefix(jobPrefix)
	                .orElseThrow(() -> new ResourceNotFoundException("No job found for prefix: " + jobPrefix));

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        // The slot is parsed and checked against the business clock at the endpoint,
	        // before any mail goes out; reaching here without one is a programming error.
	        if (examSlot == null) {
	            throw new IllegalArgumentException("Date & time is required.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Validate workflow transition: SHORTLISTED → ACKNOWLEDGED
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.ACKNOWLEDGED);

	        try {
	            application.setStatus(ApplicationStatus.ACKNOWLEDGED);
	            application.setConfirmationStatus("Confirmation Sent");
	            application.setExamDate(examSlot.toLocalDate());
	            application.setExamTime(examSlot.toLocalTime());
	            applicationForCandidateRepository.save(application);

	            String acknowledgeUrl = baseUrl +"/api/job-applications/acknowledge?jobPrefix="
	                    + jobPrefix + "&email=" + email;

	            // Prepare params for universal email
	            Map<String, Object> emailParams = new HashMap<>();
	            emailParams.put("recipientEmail", email);
	            emailParams.put("firstName", application.getFirstName());
	            emailParams.put("lastName", application.getLastName());
	            emailParams.put("jobTitle", jobs.getJobTitle());
	            emailParams.put("jobPrefix", jobPrefix);
	            // The template formats this for display; never hand it a pre-formatted or
	            // ISO string.
	            emailParams.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM, examSlot);
	            emailParams.put("acknowledgeUrl", acknowledgeUrl);
	            emailParams.put("mobileNumber", application.getMobileNumber());

	            emailService.sendUniversalEmail(EmailType.ACKNOWLEDGEMENT, emailParams);

	            whatsAppService.sendWhatsAppMessage(
	                    application.getMobileNumber(),
	                    WhatsAppService.MessageType.EXAM_SCHEDULE,
	                    examSlot,
	                    examSlot.plusHours(1)
	                );

	        } catch (Exception e) {
	            throw new RuntimeException("Error during acknowledgement process: " + e.getMessage());
	        }
	    }


	    
	    @Override
	    public String acknowledgeCandidate(String jobPrefix, String email) {
	        List<JobApplicationForCandidate> applications =
	                applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException(
	                "No matching application found for the provided jobPrefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        if (!"Confirmation Sent".equalsIgnoreCase(application.getConfirmationStatus())) {
	            return "Acknowledgement not allowed. Current status: "
	                   + application.getConfirmationStatus();
	        }

	        if (application.getStatus() != ApplicationStatus.ACKNOWLEDGED) {
	            return "Cannot acknowledge. Current status: " + application.getStatus();
	        }

	        // The candidate confirming is the reconfirmation, so carry them all the
	        // way to RECONFIRMED here rather than parking them at ACKNOWLEDGED_BACK.
	        //
	        // That stop was a dead end in practice: nothing moved an application on
	        // from it, so a candidate who had replied sat there while the admin was
	        // shown a stage with no forward action, and the exam could not be
	        // assigned because assignment starts at RECONFIRMED. Both steps are
	        // still taken in order, so the audit columns record what happened.
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.ACKNOWLEDGED_BACK);
	        application.setStatus(ApplicationStatus.ACKNOWLEDGED_BACK);
	        application.setAcknowledgedStatus("Acknowledged Back");

	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.RECONFIRMED);
	        application.setStatus(ApplicationStatus.RECONFIRMED);
	        // Not "Re-confirmation Mail Sent": no reconfirmation mail goes out on
	        // this path. The candidate is already being sent the acknowledgement
	        // confirmation below, and a second mail saying the same thing is noise.
	        application.setReconfirmationStatus("Reconfirmed on candidate acknowledgement");
	        applicationForCandidateRepository.save(application);

	        logger.info("Candidate {} acknowledged on job {}: ACKNOWLEDGED -> RECONFIRMED", email, jobPrefix);

	        // Prepare params for universal email
	        Map<String, Object> emailParams = new HashMap<>();
	        emailParams.put("recipientEmail", email);
	        emailParams.put("firstName", application.getFirstName());
	        emailParams.put("lastName", application.getLastName());
	        emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	        emailParams.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM,
	                LocalDateTime.of(application.getExamDate(), application.getExamTime()));
	        emailParams.put("mobileNumber", application.getMobileNumber());

	        emailService.sendUniversalEmail(EmailType.ACKNOWLEDGEMENT_CONFIRMATION, emailParams);

	        // WhatsApp notification remains the same
	        whatsAppService.sendWhatsAppMessage(
	                application.getMobileNumber(),
	                WhatsAppService.MessageType.ACKNOWLEDGED,
	                LocalDateTime.of(application.getExamDate(), application.getExamTime()),
	                LocalDateTime.of(application.getExamDate(), application.getExamTime().plusHours(1))
	            );

	        return "Acknowledgement received successfully.";
	    }

	    @Override
	    public void sendReconfirmationMail(String jobPrefix, String email) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository
	                .findByJobPrefixAndEmail(jobPrefix, email);

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Validate workflow transition: ACKNOWLEDGED_BACK → RECONFIRMED
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.RECONFIRMED);

	        if (application.getExamDate() == null || application.getExamTime() == null) {
	            throw new ResourceNotFoundException("Exam date or time not found. Please send acknowledgement mail first.");
	        }

	        application.setStatus(ApplicationStatus.RECONFIRMED);
	        application.setReconfirmationStatus("Re-confirmation Mail Sent");
	        applicationForCandidateRepository.save(application);

	        // Prepare params for universal email
	        Map<String, Object> emailParams = new HashMap<>();
	        emailParams.put("recipientEmail", email);
	        emailParams.put("firstName", application.getFirstName());
	        emailParams.put("lastName", application.getLastName());
	        emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	        emailParams.put("jobPrefix", jobPrefix);
	        emailParams.put(EmailServiceImpl.EXAM_SCHEDULE_PARAM,
	                LocalDateTime.of(application.getExamDate(), application.getExamTime()));
	        emailParams.put("mobileNumber", application.getMobileNumber());

	        emailService.sendUniversalEmail(EmailType.RECONFIRMATION, emailParams);

	        whatsAppService.sendWhatsAppMessage(
	                application.getMobileNumber(),
	                WhatsAppService.MessageType.RECONFIRMATION,
	                LocalDateTime.of(application.getExamDate(), application.getExamTime()),
	                LocalDateTime.of(application.getExamDate(), application.getExamTime().plusHours(1))
	            );
	    }
	    
	    @Override
	    public void sendRejectionMail(String jobPrefix, String email) {
	        List<JobApplicationForCandidate> applications =
	                applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Validate workflow transition: only certain stages allow rejection
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.REJECTED);

	        application.setStatus(ApplicationStatus.REJECTED);
	        application.setRejectionStatus("Rejection Mail Sent");
	        applicationForCandidateRepository.save(application);

	        // Prepare params for universal email
	        Map<String, Object> emailParams = new HashMap<>();
	        emailParams.put("recipientEmail", email);
	        emailParams.put("firstName", application.getFirstName());
	        emailParams.put("lastName", application.getLastName());
	        emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	        emailParams.put("mobileNumber", application.getMobileNumber());

	        emailService.sendUniversalEmail(EmailType.REJECTION, emailParams);

	        whatsAppService.sendWhatsAppMessage(
	                application.getMobileNumber(),
	                WhatsAppService.MessageType.REJECTION,
	                application.getFirstName() + " " + application.getLastName()
	            );
	    }

	    
	    

	    @Override
	    public void updateWrittenTestStatus(String jobPrefix, String email, boolean isAptitudePassed, boolean isProgrammingPassed) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No job application found for the given job prefix and email");
	        }

	        String status = (isAptitudePassed && isProgrammingPassed) ? "Passed" : "Failed";

	        for (JobApplicationForCandidate application : applications) {
	            application.setWrittenTestStatus(status);
	            applicationForCandidateRepository.save(application);
	        }
	    }
	    
	    @Override
	    public void sendSuccessMail(String jobPrefix, String email) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);
	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Validate workflow transition: INTERVIEW_COMPLETED → SELECTED
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.SELECTED);

	        application.setStatus(ApplicationStatus.SELECTED);
	        applicationForCandidateRepository.save(application);

	        // Prepare params for universal email
	        Map<String, Object> emailParams = new HashMap<>();
	        emailParams.put("recipientEmail", email);
	        emailParams.put("firstName", application.getFirstName());
	        emailParams.put("lastName", application.getLastName());
	        emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	        emailParams.put("mobileNumber", application.getMobileNumber());

	        emailService.sendUniversalEmail(EmailType.WRITTEN_TEST_SUCCESS, emailParams);

	        whatsAppService.sendWhatsAppMessage(
	                application.getMobileNumber(),
	                WhatsAppService.MessageType.SHORTLIST,
	                application.getFirstName() + " " + application.getLastName()
	            );
	    }

	    @Override
	    public void sendFailureMail(String jobPrefix, String email) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository.findByJobPrefixAndEmail(jobPrefix, email);
	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Validate workflow transition: reject from allowed stages
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.REJECTED);

	        application.setStatus(ApplicationStatus.REJECTED);
	        application.setRejectionStatus("Failed - Rejection Mail Sent");
	        applicationForCandidateRepository.save(application);

	        // Prepare params for universal email
	        Map<String, Object> emailParams = new HashMap<>();
	        emailParams.put("recipientEmail", email);
	        emailParams.put("firstName", application.getFirstName());
	        emailParams.put("lastName", application.getLastName());
	        emailParams.put("jobTitle", application.getJobPost().getJobTitle());
	        emailParams.put("mobileNumber", application.getMobileNumber());

	        emailService.sendUniversalEmail(EmailType.WRITTEN_TEST_FAILURE, emailParams);

	        whatsAppService.sendWhatsAppMessage(
	                application.getMobileNumber(),
	                WhatsAppService.MessageType.TestFailed
	            );
	    }
	    
	    

	    @Override
	    @Transactional
	    public void sendExamLink(String jobPrefix, String email, LocalDateTime examSlot) {
	        List<JobApplicationForCandidate> applications = applicationForCandidateRepository
	                .findByJobPrefixAndEmail(jobPrefix, email);

	        if (applications.isEmpty()) {
	            throw new ResourceNotFoundException("No application found for the given job prefix and email.");
	        }

	        JobApplicationForCandidate application = applications.get(0);

	        // Source of truth: an exam must actually be assigned before we can send its
	        // link. Without this guard the candidate gets marked EXAM_SENT but has no
	        // assessment to take. Do NOT change status if none exists.
	        if (!assessmentRepository.existsByCandidateEmailAndJobPrefix(email, jobPrefix)) {
	            throw new IllegalStateException(
	                    "No assessment assigned for " + email + " on job " + jobPrefix
	                            + ". Assign an exam first (POST /api/assign) before sending the exam link.");
	        }

	        // Validate workflow transition: RECONFIRMED → EXAM_SENT
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.EXAM_SENT);

	        // No slot supplied means "start now" — in the business timezone, so a
	        // UTC-hosted server does not tell the candidate an exam started 5:30 ago.
	        LocalDateTime startTime = examSlot != null ? examSlot : businessSchedule.now();
	        LocalDateTime endTime = startTime.plusHours(1);

	        // Send the exam-schedule email FIRST. If it fails it throws, the transaction
	        // rolls back, and the candidate is NOT left marked EXAM_SENT.
	        emailService.sendExamLink(email, startTime, endTime, jobPrefix);

	        // Only after the email succeeds do we commit the status change.
	        application.setStatus(ApplicationStatus.EXAM_SENT);
	        application.setExamLinkStatus("Exam Link Sent");
	        applicationForCandidateRepository.save(application);

	        // WhatsApp is a best-effort secondary channel: its failure must not undo a
	        // successfully-sent exam link.
	        try {
	            whatsAppService.sendWhatsAppMessage(
	                    application.getMobileNumber(),
	                    WhatsAppService.MessageType.EXAM_SCHEDULE,
	                    startTime,
	                    endTime
	            );
	        } catch (Exception e) {
	            logger.warn("WhatsApp exam-schedule notification failed for {} (job {}): {}",
	                    email, jobPrefix, e.getMessage());
	        }
	    }

	    //method to schedule interview
	    public JobApplicationForCandidate scheduleInterview(String jobPrefix, String email) {

	        JobApplicationForCandidate application = applicationForCandidateRepository
	                .findByJobPost_JobPrefixAndUser_Email(jobPrefix, email)
	                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));

	        // Validate workflow transition: EXAM_COMPLETED → INTERVIEW_SCHEDULED
	        StatusTransitionValidator.validate(application.getStatus(), ApplicationStatus.INTERVIEW_SCHEDULED);

	        application.setStatus(ApplicationStatus.INTERVIEW_SCHEDULED);
	        application.setInterview("Scheduled");

	        return applicationForCandidateRepository.save(application);
	    }


    
}