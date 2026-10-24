package com.rightpath.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.enums.AttemptStatus;

public interface CandidateInterviewScheduleRepository extends JpaRepository<CandidateInterviewSchedule, Long> {

	/**
	 * Just the status. The phone asks whether its interview is over every few seconds, and loading the whole
	 * row for that drags in the transcript, evaluation and recording columns.
	 */
	@Query("SELECT c.attemptStatus FROM CandidateInterviewSchedule c WHERE c.id = :id")
	Optional<AttemptStatus> findAttemptStatusById(@Param("id") Long id);

	Optional<CandidateInterviewSchedule> findByJobPrefixAndEmail(String jobPrefix, String email);

	Optional<CandidateInterviewSchedule> findFirstByJobPrefixAndEmailOrderByAssignedAtDesc(String jobPrefix, String email);

	List<CandidateInterviewSchedule> findByEmailAndAttemptStatus(String email, String attemptStatus);

	// Optional: With deadline validation
		@Query("SELECT c FROM CandidateInterviewSchedule c WHERE c.email = :email AND c.attemptStatus = com.rightpath.enums.AttemptStatus.NOT_ATTEMPTED")
	List<CandidateInterviewSchedule> findActiveInterviewsByEmail(String email);

	List<CandidateInterviewSchedule> findAllByJobPrefix(String jobPrefix);

	// Security: ownership check
	Optional<CandidateInterviewSchedule> findByIdAndEmail(Long id, String email);

	// Resume: find by id and status
	Optional<CandidateInterviewSchedule> findByIdAndAttemptStatus(Long id, AttemptStatus attemptStatus);

	// Timeout: find stale in-progress interviews
	List<CandidateInterviewSchedule> findByAttemptStatusAndStartedAtBefore(AttemptStatus attemptStatus, LocalDateTime cutoff);
	
	Optional<CandidateInterviewSchedule> findTopByJobPrefixAndEmailOrderByAssignedAtDesc(
	        String jobPrefix, String email);

	/**
	 * Add one camera recording part to the interview's list, in the database.
	 *
	 * <p>A single UPDATE that touches only this column, rather than reading the
	 * list into the application, adding to it and saving the whole row. That
	 * read-modify-write is how earlier parts were lost: anything else writing
	 * the same row in between — another upload, the evaluation, a warning —
	 * made one side overwrite the other, and an interview ended up with only
	 * its last part. The database serialises concurrent updates to a row, so
	 * parts arriving together are all kept.</p>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE CandidateInterviewSchedule c SET c.recordReferences = "
			+ "CASE WHEN c.recordReferences IS NULL OR c.recordReferences = '' THEN :ref "
			+ "ELSE CONCAT(c.recordReferences, :suffix) END WHERE c.id = :id")
	int appendRecordReference(@Param("id") Long id, @Param("ref") String ref, @Param("suffix") String suffix);

	/** The same, for the paired phone's recording's parts. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE CandidateInterviewSchedule c SET c.mobileRecordReferences = "
			+ "CASE WHEN c.mobileRecordReferences IS NULL OR c.mobileRecordReferences = '' THEN :ref "
			+ "ELSE CONCAT(c.mobileRecordReferences, :suffix) END WHERE c.id = :id")
	int appendMobileRecordReference(@Param("id") Long id, @Param("ref") String ref, @Param("suffix") String suffix);

	/** The same, for the shared-screen recording's parts. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE CandidateInterviewSchedule c SET c.screenRecordReferences = "
			+ "CASE WHEN c.screenRecordReferences IS NULL OR c.screenRecordReferences = '' THEN :ref "
			+ "ELSE CONCAT(c.screenRecordReferences, :suffix) END WHERE c.id = :id")
	int appendScreenRecordReference(@Param("id") Long id, @Param("ref") String ref, @Param("suffix") String suffix);
}
