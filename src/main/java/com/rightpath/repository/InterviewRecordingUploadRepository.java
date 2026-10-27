package com.rightpath.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.InterviewRecordingUpload;

/**
 * Every state change is one UPDATE that names the state it expects, so two
 * requests racing over the same upload cannot both win.
 */
public interface InterviewRecordingUploadRepository extends JpaRepository<InterviewRecordingUpload, Long> {

	Optional<InterviewRecordingUpload> findFirstByUploadId(String uploadId);

	/** The upload that produced a stored recording, to say when it was made. */
	Optional<InterviewRecordingUpload> findFirstByStoredReference(String storedReference);

	List<InterviewRecordingUpload> findByStatusAndLastActivityAtBefore(String status, LocalDateTime cutoff);

	/** A piece arrived: count it and note the upload is alive. Only while it is still open. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewRecordingUpload u SET u.lastActivityAt = :now, u.partsReceived = u.partsReceived + 1 "
			+ "WHERE u.uploadId = :uploadId AND u.status = 'OPEN'")
	int touch(@Param("uploadId") String uploadId, @Param("now") LocalDateTime now);

	/** Take the right to join the pieces. Returns 0 if somebody else has it or it is already done. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewRecordingUpload u SET u.status = 'COMPLETING', u.lastActivityAt = :now "
			+ "WHERE u.id = :id AND u.status = 'OPEN'")
	int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

	/** Give the claim back after a failed join, so it can be tried again. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewRecordingUpload u SET u.status = 'OPEN', u.lastActivityAt = :now "
			+ "WHERE u.id = :id AND u.status = 'COMPLETING'")
	int release(@Param("id") Long id, @Param("now") LocalDateTime now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewRecordingUpload u SET u.status = 'COMPLETED', u.storedReference = :reference "
			+ "WHERE u.id = :id")
	int markCompleted(@Param("id") Long id, @Param("reference") String reference);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewRecordingUpload u SET u.status = 'ABORTED' WHERE u.id = :id AND u.status = 'OPEN'")
	int markAborted(@Param("id") Long id);
}
