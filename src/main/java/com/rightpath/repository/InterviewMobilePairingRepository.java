package com.rightpath.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.InterviewMobilePairing;

public interface InterviewMobilePairingRepository extends JpaRepository<InterviewMobilePairing, String> {

	java.util.List<InterviewMobilePairing> findByInterviewScheduleId(Long interviewScheduleId);

	/** Notes that the phone has just been heard from. One UPDATE, and only for a pairing still open. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewMobilePairing p SET p.lastSeenAt = :now WHERE p.token = :token "
			+ "AND (p.revoked IS NULL OR p.revoked = false)")
	int touchSeen(@Param("token") String token, @Param("now") java.time.LocalDateTime now);

	/** Open pairings whose phone appeared at some point and has not been heard from since the cutoff. */
	@Query("SELECT p FROM InterviewMobilePairing p WHERE p.lastSeenAt IS NOT NULL AND p.lastSeenAt < :cutoff "
			+ "AND (p.revoked IS NULL OR p.revoked = false)")
	java.util.List<InterviewMobilePairing> findSilentSince(@Param("cutoff") java.time.LocalDateTime cutoff);

	/** Counted in one UPDATE, so two pieces arriving together are both counted. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewMobilePairing p SET p.bytesReceived = p.bytesReceived + :bytes WHERE p.token = :token")
	int addBytes(@Param("token") String token, @Param("bytes") long bytes);
}
