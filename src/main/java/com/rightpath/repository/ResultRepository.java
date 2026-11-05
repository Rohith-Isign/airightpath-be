package com.rightpath.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.rightpath.entity.Result;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long> {

	/**
	 * Retrieves a list of results associated with a specific candidate's email.
	 *
	 * @param candidateEmail The email address of the candidate.
	 * @return A list of `Result` entities linked to the specified candidate email.
	 */
	List<Result> findByCandidateEmail(String candidateEmail);
	
	
	List<Result> findByCandidateEmailAndJobPrefix(String candidateEmail, String jobPrefix);

	List<Result> findByJobPrefix(String jobPrefix);

}
