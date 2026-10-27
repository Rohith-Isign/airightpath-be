package com.rightpath.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rightpath.dto.QuestionBankAssignmentDTO;
import com.rightpath.dto.QuestionBankItemDTO;
import com.rightpath.dto.QuestionBankSyncStatus;
import com.rightpath.enums.AssessmentType;
import com.rightpath.entity.JobPost;
import com.rightpath.entity.QuestionBankItem;
import com.rightpath.repository.AssessmentPaperRef;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.JobPostRepository;
import com.rightpath.repository.QuestionBankAssignmentRepository;
import com.rightpath.repository.QuestionBankAssignmentRepository.UsageRow;
import com.rightpath.repository.QuestionBankItemRepository;

/**
 * The question bank: every question, how often it was used, and who received it.
 *
 * <p>Reading is cheap and always available. {@link #startSync} is the one-off (and repeatable)
 * job that reads the papers already sitting in storage from before questions were tracked,
 * and attributes each to the candidate it was given to. It runs in the background because a
 * paper is a storage download and there is one per candidate.</p>
 */
@Service
public class QuestionBankService {

	private static final Logger log = LoggerFactory.getLogger(QuestionBankService.class);

	private final QuestionBankItemRepository items;
	private final QuestionBankAssignmentRepository assignments;
	private final AssessmentRepository assessmentRepository;
	private final JobPostRepository jobPostRepository;
	private final StorageService storageService;
	private final QuestionBankTracker tracker;

	@Value("${aws.s3.prefix.exam:exam}")
	private String examPrefix;

	private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "question-bank-sync");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicReference<QuestionBankSyncStatus> status = new AtomicReference<>(QuestionBankSyncStatus.idle());

	public QuestionBankService(QuestionBankItemRepository items, QuestionBankAssignmentRepository assignments,
			AssessmentRepository assessmentRepository, JobPostRepository jobPostRepository,
			StorageService storageService, QuestionBankTracker tracker) {
		this.items = items;
		this.assignments = assignments;
		this.assessmentRepository = assessmentRepository;
		this.jobPostRepository = jobPostRepository;
		this.storageService = storageService;
		this.tracker = tracker;
	}

	public List<QuestionBankItemDTO> listItems() {
		Map<Long, UsageRow> usage = new HashMap<>();
		assignments.usageByItem().forEach(row -> usage.put(row.getItemId(), row));

		// Deleted jobs included: their questions were still asked.
		Map<String, String> titles = jobPostRepository.findAll().stream()
				.filter(job -> job.getJobPrefix() != null)
				.collect(Collectors.toMap(JobPost::getJobPrefix, job -> job.getJobTitle() == null ? "" : job.getJobTitle(),
						(first, second) -> first));

		return items.findAll().stream()
				.map(item -> toDto(item, usage.get(item.getId()), titles.getOrDefault(item.getJobPrefix(), "")))
				.sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
				.toList();
	}

	private static QuestionBankItemDTO toDto(QuestionBankItem item, UsageRow usage, String jobTitle) {
		return new QuestionBankItemDTO(item.getId(), item.getJobPrefix(), jobTitle, item.getAssessmentType().name(),
				item.getContent(), item.getDifficulty(), item.getMarks(), item.getSource(), item.getCreatedBy(),
				item.getCreatedAt(), usage == null ? 0 : usage.getTimesAssigned(),
				usage == null ? 0 : usage.getUniqueCandidates(), usage == null ? null : usage.getLastAssignedAt());
	}

	public List<QuestionBankAssignmentDTO> assignmentsFor(Long itemId) {
		return assignments.findByItemIdOrderByAssignedAtDesc(itemId).stream()
				.map(row -> new QuestionBankAssignmentDTO(row.getAssessmentId(), row.getCandidateEmail(),
						row.getJobPrefix(), row.getAssignedAt()))
				.toList();
	}

	public QuestionBankSyncStatus syncStatus() {
		return status.get();
	}

	/** Begin reading stored papers into the bank, unless that is already under way. */
	public synchronized QuestionBankSyncStatus startSync(String actor) {
		if (status.get().running()) {
			return status.get();
		}
		status.set(new QuestionBankSyncStatus(true, 0, 0, 0, 0, 0, LocalDateTime.now(), null, "Starting"));
		syncExecutor.submit(() -> runSync(actor));
		return status.get();
	}

	private void runSync(String actor) {
		LocalDateTime started = status.get().startedAt();
		int processed = 0;
		int failed = 0;
		int newQuestions = 0;
		int newAssignments = 0;
		try {
			var already = assignments.findTrackedAssessmentIds();
			List<AssessmentPaperRef> todo = assessmentRepository.findPaperRefs().stream()
					.filter(ref -> !already.contains(ref.getId()))
					.toList();
			long itemsBefore = items.count();
			int total = todo.size();
			status.set(new QuestionBankSyncStatus(true, 0, total, 0, 0, 0, started, null, "Reading papers"));

			for (AssessmentPaperRef ref : todo) {
				try {
					String paper = paperOf(ref);
					newAssignments += tracker.recordAssignment(ref.getJobPrefix(), ref.getAssessmentType(), paper,
							ref.getId(), ref.getCandidateEmail(), ref.getAssignedAt(), ref.getUploadedBy() != null ? ref.getUploadedBy() : actor);
				} catch (Exception e) {
					failed++;
					log.warn("Question bank sync: could not read the paper of assessment {} ({})", ref.getId(),
							e.getMessage());
				}
				processed++;
				if (processed % 10 == 0 || processed == total) {
					newQuestions = (int) (items.count() - itemsBefore);
					status.set(new QuestionBankSyncStatus(true, processed, total, newQuestions, newAssignments, failed,
							started, null, "Reading papers"));
				}
			}

			// Papers sitting in storage that no assessment points to: generated or uploaded, never given to
			// anyone. They belong in the bank as "never assigned".
			java.util.Set<String> linked = assessmentRepository.findPaperRefs().stream()
					.map(AssessmentPaperRef::getFileName).filter(java.util.Objects::nonNull)
					.collect(Collectors.toSet());
			List<String> stored = storageService.listFileNames(examPrefix).stream()
					.filter(name -> name.toLowerCase().endsWith(".json") && !linked.contains(name))
					.toList();
			status.set(new QuestionBankSyncStatus(true, processed, total + stored.size(), 0, newAssignments, failed,
					started, null, "Reading unassigned papers from storage"));
			int unreadable = 0;
			for (String name : stored) {
				String[] id = identify(name);
				if (id == null) {
					unreadable++;
					log.info("Question bank sync: skipped {} (name does not say job and paper type)", name);
				} else {
					try {
						tracker.recordStored(id[0], AssessmentType.valueOf(id[1]),
								storageService.downloadFileAsText(examPrefix, name), actor);
					} catch (Exception e) {
						failed++;
						log.warn("Question bank sync: could not read stored paper {} ({})", name, e.getMessage());
					}
				}
				processed++;
			}
			newQuestions = (int) (items.count() - itemsBefore);
			String message = "Finished; " + stored.size() + " unassigned file(s) found in storage";
			if (unreadable > 0) {
				message += ", " + unreadable + " skipped (name has no job/type)";
			}
			if (failed > 0) {
				message += ", " + failed + " could not be read";
			}
			status.set(new QuestionBankSyncStatus(false, processed, total + stored.size(), newQuestions, newAssignments,
					failed, started, LocalDateTime.now(), message));
		} catch (Exception e) {
			log.error("Question bank sync failed", e);
			status.set(new QuestionBankSyncStatus(false, processed, 0, newQuestions, newAssignments, failed, started,
					LocalDateTime.now(), "Failed: " + e.getMessage()));
		}
	}

	/** Job prefix and paper type from a stored name like {@code JOB_aptitude_who_ts.json}, else null. */
	static String[] identify(String name) {
		String lower = name.toLowerCase();
		for (String type : new String[] { "aptitude", "coding" }) {
			int at = lower.indexOf("_" + type + "_");
			if (at > 0) {
				return new String[] { name.substring(0, at), type.toUpperCase() };
			}
		}
		return null;
	}

	/** The paper for an assessment: its stored file, or the text kept on the row by older uploads. */
	private String paperOf(AssessmentPaperRef ref) {
		if (ref.getFileName() != null && !ref.getFileName().isBlank() && ref.getContainerName() != null) {
			return storageService.downloadFileAsText(ref.getContainerName(), ref.getFileName());
		}
		return assessmentRepository.findById(ref.getId()).map(a -> a.getQuestionPaper()).orElse(null);
	}
}
