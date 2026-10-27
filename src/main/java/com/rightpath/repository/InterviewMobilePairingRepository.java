package com.rightpath.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.entity.InterviewMobilePairing;

public interface InterviewMobilePairingRepository extends JpaRepository<InterviewMobilePairing, String> {

	java.util.List<InterviewMobilePairing> findByInterviewScheduleId(Long interviewScheduleId);

	/** Counted in one UPDATE, so two pieces arriving together are both counted. */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Transactional
	@Query("UPDATE InterviewMobilePairing p SET p.bytesReceived = p.bytesReceived + :bytes WHERE p.token = :token")
	int addBytes(@Param("token") String token, @Param("bytes") long bytes);
}
