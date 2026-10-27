package com.rightpath.service.impl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.rightpath.dto.JobListingResponse;
import com.rightpath.dto.JobPostDTO;
import com.rightpath.dto.JobPostDeletionDTO;
import com.rightpath.dto.JobPostSearchRequest;
import com.rightpath.dto.JobStatusCountsDTO;
import com.rightpath.entity.JobApplicationForCandidate;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.Users;
import com.rightpath.enums.JobStatusFilter;
import com.rightpath.exceptions.JobDeadlineInPastException;
import com.rightpath.exceptions.JobPostNotFoundException;
import com.rightpath.exceptions.JobPrefixImmutableException;
import com.rightpath.repository.JobApplicationForCandidateRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.JobPostSpecifications;
import com.rightpath.repository.JobPostRepository.JobTypeCount;
import com.rightpath.repository.UsersRepository;
import com.rightpath.service.JobPostService;
import com.rightpath.util.BusinessSchedule;

import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class JobPostServiceImpl implements JobPostService {

	@Autowired
	private JobPostRepository repository;
	@Autowired
	private UsersRepository usersRepository;
	@Autowired
	private JobApplicationForCandidateRepository jobApplicationRepository;

	/**
	 * Deadlines are plain dates, so "is this job still active?" must be answered
	 * against the date it is in the business timezone — not in the caller's zone and
	 * not in the database session's zone, either of which could expire a posting
	 * hours early or late.
	 */
	@Autowired
	private BusinessSchedule businessSchedule;

	/** Default page size when the client does not ask for one. */
	private static final int DEFAULT_PAGE_SIZE = 20;

	/** Hard ceiling on page size, to keep a single request bounded. */
	private static final int MAX_PAGE_SIZE = 100;

	/** Applied when the client does not ask for a specific ordering. */
	private static final String DEFAULT_SORT = "applicationDeadline,asc";

	/**
	 * Sortable fields, keyed by their lower-cased request spelling. Restricting the
	 * set keeps an unknown/unsortable property from reaching the query, and keeps
	 * the supported orderings visible in one place.
	 */
	private static final Map<String, String> SORTABLE_FIELDS = Map.of(
			"applicationdeadline", JobPostSpecifications.FIELD_APPLICATION_DEADLINE,
			"createdat", "createdAt",
			"jobtitle", "jobTitle",
			"companyname", "companyName",
			"location", "location",
			"jobtype", JobPostSpecifications.FIELD_JOB_TYPE,
			"id", "id");

	/**
	 * Final tiebreaker on every ordering. Without it, rows sharing a deadline (or a
	 * creation date) could be returned in a different order per page request, so
	 * paging would silently duplicate and skip rows.
	 */
	private static final Sort.Order ID_TIEBREAKER = Sort.Order.desc("id");

	public JobPost createJobPost(JobPostDTO dto) {
		Long lastId = repository.findTopByOrderByIdDesc().map(JobPost::getId).orElse(0L);

		String prefix = dto.getJobPrefix() != null ? dto.getJobPrefix().toUpperCase() : "JOB";
		String jobCode = String.format("%s-%03d", prefix, lastId + 1);

		JobPost jobPost = JobPost.builder().jobPrefix(jobCode).jobTitle(dto.getJobTitle())
				.companyName(dto.getCompanyName()).location(dto.getLocation()).jobDescription(dto.getJobDescription())
				.keySkills(dto.getKeySkills()).experience(dto.getExperience()).education(dto.getEducation())
				.salaryRange(dto.getSalaryRange()).jobType(dto.getJobType()).industry(dto.getIndustry())
				.department(dto.getDepartment()).role(dto.getRole()).numberOfOpenings(dto.getNumberOfOpenings())
				.contactEmail(dto.getContactEmail()).applicationDeadline(dto.getApplicationDeadline())
				.createdAt(businessSchedule.today()).build();

		return repository.save(jobPost);
	}

	@Override
	@Transactional
	public JobPost updateJobPost(Long id, JobPostDTO dto) {
		JobPost existing = repository.findById(id).orElseThrow(() -> new JobPostNotFoundException(id));

		requireUnchangedPrefix(existing, dto);
		requireDeadlineNotMovedIntoPast(existing, dto);

		// Full replace of the editable fields. jobPrefix and createdAt are deliberately
		// absent: the prefix is immutable, and the creation date is not the admin's to
		// rewrite.
		List<String> changes = new ArrayList<>();
		existing.setJobTitle(tracked(changes, "jobTitle", existing.getJobTitle(), dto.getJobTitle()));
		existing.setCompanyName(tracked(changes, "companyName", existing.getCompanyName(), dto.getCompanyName()));
		existing.setLocation(tracked(changes, "location", existing.getLocation(), dto.getLocation()));
		existing.setJobDescription(
				tracked(changes, "jobDescription", existing.getJobDescription(), dto.getJobDescription()));
		existing.setKeySkills(tracked(changes, "keySkills", existing.getKeySkills(), dto.getKeySkills()));
		existing.setExperience(tracked(changes, "experience", existing.getExperience(), dto.getExperience()));
		existing.setEducation(tracked(changes, "education", existing.getEducation(), dto.getEducation()));
		existing.setSalaryRange(tracked(changes, "salaryRange", existing.getSalaryRange(), dto.getSalaryRange()));
		existing.setJobType(tracked(changes, "jobType", existing.getJobType(), dto.getJobType()));
		existing.setIndustry(tracked(changes, "industry", existing.getIndustry(), dto.getIndustry()));
		existing.setDepartment(tracked(changes, "department", existing.getDepartment(), dto.getDepartment()));
		existing.setRole(tracked(changes, "role", existing.getRole(), dto.getRole()));
		existing.setNumberOfOpenings(
				tracked(changes, "numberOfOpenings", existing.getNumberOfOpenings(), dto.getNumberOfOpenings()));
		existing.setContactEmail(tracked(changes, "contactEmail", existing.getContactEmail(), dto.getContactEmail()));
		existing.setApplicationDeadline(tracked(changes, "applicationDeadline",
				existing.getApplicationDeadline(), dto.getApplicationDeadline()));

		existing.setUpdatedAt(businessSchedule.now());
		existing.setUpdatedBy(actingUser());

		JobPost saved = repository.save(existing);
		// Lightweight audit trail: who edited which fields of a posting candidates may
		// already have applied to.
		log.info("Job post {} ({}) updated by {}; changes: {}", saved.getId(), saved.getJobPrefix(),
				saved.getUpdatedBy(), changes.isEmpty() ? "none" : String.join("; ", changes));
		return saved;
	}

	@Override
	@Transactional
	public JobPostDeletionDTO deleteJobPost(Long id) {
		JobPost live = repository.findById(id)
				.filter(post -> post.getDeletedAt() == null)
				.orElseThrow(() -> new JobPostNotFoundException(id));

		long applications = jobApplicationRepository.findByJobPost(live).size();

		live.setDeletedAt(businessSchedule.now());
		live.setDeletedBy(actingUser());
		JobPost archived = repository.save(live);

		// Deliberately loud: this hides a posting candidates may have applied to, and the
		// retained count is what makes the soft delete auditable after the fact.
		log.info("Job post {} ({}) archived by {}; {} application(s) retained", archived.getId(),
				archived.getJobPrefix(), archived.getDeletedBy(), applications);

		return new JobPostDeletionDTO(archived.getId(), archived.getJobPrefix(), archived.getDeletedAt(), applications);
	}

	/**
	 * Rejects a prefix change. A missing or blank prefix in the payload is treated as
	 * "unchanged" rather than an attempt to clear it, so a client that does not echo
	 * the field still gets a successful edit; a <em>different</em> prefix is refused.
	 */
	private static void requireUnchangedPrefix(JobPost existing, JobPostDTO dto) {
		String requested = dto.getJobPrefix() == null ? null : dto.getJobPrefix().trim();
		if (requested == null || requested.isEmpty()) {
			return;
		}
		if (!requested.equalsIgnoreCase(existing.getJobPrefix())) {
			throw new JobPrefixImmutableException(existing.getJobPrefix(), dto.getJobPrefix());
		}
	}

	/**
	 * Allows an expired posting to keep its own past deadline — an admin correcting a
	 * typo on a closed job must not be forced to reopen it — while requiring any
	 * <em>new</em> deadline to be today or later. Clearing the deadline is permitted:
	 * a posting with no deadline never expires.
	 */
	private void requireDeadlineNotMovedIntoPast(JobPost existing, JobPostDTO dto) {
		LocalDate requested = dto.getApplicationDeadline();
		if (requested == null || requested.equals(existing.getApplicationDeadline())) {
			return;
		}
		LocalDate today = businessSchedule.today();
		if (requested.isBefore(today)) {
			throw new JobDeadlineInPastException(requested, today);
		}
	}

	/** Records a field change for the audit log and returns the value to store. */
	private static <T> T tracked(List<String> changes, String field, T current, T incoming) {
		if (!Objects.equals(current, incoming)) {
			changes.add(field + ": '" + current + "' -> '" + incoming + "'");
		}
		return incoming;
	}

	/**
	 * Email of the authenticated admin, as set by the JWT filter. Falls back to
	 * "system" for non-request callers (seeders, scheduled jobs) so the audit line is
	 * never blank.
	 */
	private static String actingUser() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return (authentication == null || authentication.getName() == null) ? "system" : authentication.getName();
	}

	// Newest first: id is IDENTITY-generated, so higher id = more recently created.
	private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "id");

	@Override
	public List<JobPostDTO> getAllJobPosts() {
		return repository.findAll(JobPostSpecifications.notDeleted(), NEWEST_FIRST).stream()
				.map(this::convertToDTO).collect(Collectors.toList());
	}

	@Override
	public JobListingResponse searchJobPosts(JobPostSearchRequest request) {
		JobStatusFilter status = JobStatusFilter.fromParam(request.getStatus());
		Pageable pageable = toPageable(request);

		// Search and job type also bound the counts; status deliberately does not,
		// since the dropdown shows how many rows each bucket *would* return. Archived
		// postings are excluded from both, so no bucket can advertise a deleted job.
		Specification<JobPost> filters = JobPostSpecifications.combine(
				JobPostSpecifications.notDeleted(),
				JobPostSpecifications.matchesSearch(request.getSearch()),
				JobPostSpecifications.matchesJobType(request.getJobType()));
		Specification<JobPost> statusFilter = JobPostSpecifications.hasStatus(status, businessSchedule.today());

		Page<JobPostDTO> page = repository
				.findAll(JobPostSpecifications.combine(filters, statusFilter), pageable)
				.map(this::convertToDTO);

		log.debug("Job listing: status={} search={} jobType={} -> {} of {} matches, page {}/{}",
				status, request.getSearch(), request.getJobType(), page.getNumberOfElements(),
				page.getTotalElements(), page.getNumber(), page.getTotalPages());

		return new JobListingResponse(page, countBuckets(filters));
	}

	@Override
	public JobStatusCountsDTO getStatusCounts(String search, String jobType) {
		return countBuckets(JobPostSpecifications.combine(
				JobPostSpecifications.notDeleted(),
				JobPostSpecifications.matchesSearch(search),
				JobPostSpecifications.matchesJobType(jobType)));
	}

	@Override
	public List<String> getDistinctJobTypes() {
		// Group the stored spellings by the key the jobType filter compares on, so
		// each dropdown entry maps to exactly one result set. Within a group the
		// most-used spelling wins (ties broken alphabetically) to give a stable,
		// human-looking label.
		Map<String, JobTypeCount> canonicalByKey = new LinkedHashMap<>();
		for (JobTypeCount candidate : repository.findJobTypeCounts()) {
			String key = JobPostSpecifications.normalizeJobType(candidate.getJobType());
			if (key == null) {
				continue;
			}
			canonicalByKey.merge(key, candidate, JobPostServiceImpl::preferredSpelling);
		}

		List<String> jobTypes = canonicalByKey.values().stream()
				.map(jobType -> jobType.getJobType().trim())
				.sorted(Comparator.comparing(label -> label.toLowerCase()))
				.collect(Collectors.toList());
		log.debug("Resolved {} distinct job types from {} stored spellings", jobTypes.size(), canonicalByKey.size());
		return jobTypes;
	}

	/** Picks the spelling to display for a job type: most used, then alphabetical. */
	private static JobTypeCount preferredSpelling(JobTypeCount current, JobTypeCount candidate) {
		if (candidate.getOccurrences() != current.getOccurrences()) {
			return candidate.getOccurrences() > current.getOccurrences() ? candidate : current;
		}
		return candidate.getJobType().compareToIgnoreCase(current.getJobType()) < 0 ? candidate : current;
	}

	/**
	 * Counts the active bucket and the unfiltered total with the same predicates
	 * the listing uses, then derives expired — one fewer query than counting both
	 * buckets, and it guarantees {@code all == active + expired}.
	 */
	private JobStatusCountsDTO countBuckets(Specification<JobPost> filters) {
		long all = repository.count(filters);
		long active = repository.count(JobPostSpecifications.combine(filters,
				JobPostSpecifications.hasStatus(JobStatusFilter.ACTIVE, businessSchedule.today())));
		return new JobStatusCountsDTO(all, active, all - active);
	}

	/** Applies paging defaults and limits, and resolves the requested ordering. */
	private static Pageable toPageable(JobPostSearchRequest request) {
		int page = request.getPage() != null ? request.getPage() : 0;
		int requestedSize = request.getSize() != null ? request.getSize() : DEFAULT_PAGE_SIZE;
		if (page < 0 || requestedSize < 1) {
			throw new IllegalArgumentException("page must be >= 0 and size must be >= 1");
		}
		int size = Math.min(requestedSize, MAX_PAGE_SIZE);
		if (size != requestedSize) {
			log.debug("Capping requested page size {} to {}", requestedSize, MAX_PAGE_SIZE);
		}
		return PageRequest.of(page, size, parseSort(request.getSort()));
	}

	/**
	 * Parses Spring's {@code field,direction} sort syntax against
	 * {@link #SORTABLE_FIELDS}, tolerating an omitted direction (defaults to
	 * ascending) and multiple pairs ({@code createdAt,desc,jobTitle,asc}). An
	 * {@code id} tiebreaker is always appended.
	 *
	 * @param rawSort the {@code sort} parameter; {@code null}/blank uses the default
	 * @return the resolved sort, never unordered
	 * @throws IllegalArgumentException if a field is not sortable, or a direction
	 *                                  appears before any field
	 */
	static Sort parseSort(String rawSort) {
		String sortExpression = (rawSort == null || rawSort.isBlank()) ? DEFAULT_SORT : rawSort;

		List<Sort.Order> orders = new ArrayList<>();
		for (String token : sortExpression.split(",")) {
			String value = token.trim();
			if (value.isEmpty()) {
				continue;
			}
			if (value.equalsIgnoreCase("asc") || value.equalsIgnoreCase("desc")) {
				if (orders.isEmpty()) {
					throw new IllegalArgumentException(
							"Invalid sort '" + rawSort + "': direction '" + value + "' has no field before it");
				}
				int last = orders.size() - 1;
				orders.set(last, orders.get(last).with(Sort.Direction.fromString(value)));
				continue;
			}
			String property = SORTABLE_FIELDS.get(value.toLowerCase());
			if (property == null) {
				throw new IllegalArgumentException("Cannot sort by '" + value + "'. Sortable fields: "
						+ SORTABLE_FIELDS.values().stream().sorted().collect(Collectors.joining(", ")));
			}
			orders.add(Sort.Order.asc(property));
		}
		if (orders.isEmpty()) {
			throw new IllegalArgumentException("Invalid sort '" + rawSort + "': no field given");
		}

		boolean alreadyDeterministic = orders.stream().anyMatch(order -> "id".equals(order.getProperty()));
		if (!alreadyDeterministic) {
			orders.add(ID_TIEBREAKER);
		}
		return Sort.by(orders);
	}

	@Override
	public JobPostDTO convertToDTO(JobPost post) {
		// id is exposed so clients can address a posting for editing.
		return JobPostDTO.builder().id(post.getId()).jobPrefix(post.getJobPrefix()).jobTitle(post.getJobTitle())
				.companyName(post.getCompanyName()).location(post.getLocation())
				.jobDescription(post.getJobDescription()).keySkills(post.getKeySkills())
				.experience(post.getExperience()).education(post.getEducation()).salaryRange(post.getSalaryRange())
				.jobType(post.getJobType()).industry(post.getIndustry()).department(post.getDepartment())
				.role(post.getRole()).numberOfOpenings(post.getNumberOfOpenings()).contactEmail(post.getContactEmail())
				.applicationDeadline(post.getApplicationDeadline()).createdAt(post.getCreatedAt()).build();
	}

	public String applyToJob(Long jobId, String userEmail) {
		// Archived postings are closed to applications, same as on the prefix-based path.
		JobPost jobPost = repository.findById(jobId)
				.filter(post -> post.getDeletedAt() == null)
				.orElseThrow(() -> new JobPostNotFoundException(jobId));

		Users user = usersRepository.findById(userEmail).orElseThrow(() -> new RuntimeException("User not found"));

		// Check if user already applied via existing job applications
		boolean alreadyApplied = jobApplicationRepository
				.findByJobPost_JobPrefixAndUser_Email(jobPost.getJobPrefix(), userEmail)
				.isPresent();
		if (alreadyApplied) {
			return "User already applied for this job.";
		}

		JobApplicationForCandidate application = JobApplicationForCandidate.builder()
				.user(user)
				.jobPost(jobPost)
				.firstName(user.getFirstName())
				.lastName(user.getLastName())
				.build();
		jobApplicationRepository.save(application);
		return "Application submitted successfully.";
	}

	@Override
	public int getApplicationCount(Long jobId) {
		JobPost jobPost = repository.findById(jobId).orElseThrow(() -> new RuntimeException("Job not found"));
		return jobApplicationRepository.findByJobPost(jobPost).size();
	}

	@Override
	public JobPostDTO findByJobPrefix(String jobPrefix) {
		// TODO Auto-generated method stub
		return null;
	}

}
