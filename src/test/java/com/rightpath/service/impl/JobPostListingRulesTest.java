package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.rightpath.enums.JobStatusFilter;
import com.rightpath.repository.JobPostSpecifications;

/**
 * Covers the parameter rules of the paginated job listing that can be checked
 * without a database: sort parsing, status defaulting and job-type normalisation.
 */
class JobPostListingRulesTest {

	@Test
	void defaultsToDeadlineAscendingWithIdTiebreaker() {
		List<Sort.Order> orders = JobPostServiceImpl.parseSort(null).toList();

		assertEquals(2, orders.size());
		assertEquals(Sort.Order.asc("applicationDeadline"), orders.get(0));
		assertEquals(Sort.Order.desc("id"), orders.get(1));
	}

	@Test
	void parsesFieldAndDirection() {
		List<Sort.Order> orders = JobPostServiceImpl.parseSort("createdAt,desc").toList();

		assertEquals(List.of(Sort.Order.desc("createdAt"), Sort.Order.desc("id")), orders);
	}

	@Test
	void treatsFieldNamesCaseInsensitivelyAndDefaultsToAscending() {
		List<Sort.Order> orders = JobPostServiceImpl.parseSort("CREATEDAT").toList();

		assertEquals(List.of(Sort.Order.asc("createdAt"), Sort.Order.desc("id")), orders);
	}

	@Test
	void parsesMultipleSortPairs() {
		List<Sort.Order> orders = JobPostServiceImpl.parseSort("jobTitle,asc,createdAt,desc").toList();

		assertEquals(List.of(Sort.Order.asc("jobTitle"), Sort.Order.desc("createdAt"), Sort.Order.desc("id")), orders);
	}

	@Test
	void doesNotDuplicateAnExplicitIdSort() {
		List<Sort.Order> orders = JobPostServiceImpl.parseSort("id,asc").toList();

		assertEquals(List.of(Sort.Order.asc("id")), orders);
	}

	@Test
	void rejectsUnsortableField() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
				() -> JobPostServiceImpl.parseSort("jobDescription,asc"));

		assertTrue(error.getMessage().contains("jobDescription"), error.getMessage());
	}

	@Test
	void rejectsDirectionWithoutField() {
		assertThrows(IllegalArgumentException.class, () -> JobPostServiceImpl.parseSort("asc"));
	}

	@Test
	void statusDefaultsToActiveWhenAbsent() {
		assertEquals(JobStatusFilter.ACTIVE, JobStatusFilter.fromParam(null));
		assertEquals(JobStatusFilter.ACTIVE, JobStatusFilter.fromParam("  "));
	}

	@Test
	void statusIsCaseInsensitive() {
		assertEquals(JobStatusFilter.EXPIRED, JobStatusFilter.fromParam("expired"));
		assertEquals(JobStatusFilter.ALL, JobStatusFilter.fromParam(" All "));
	}

	@Test
	void rejectsUnknownStatus() {
		assertThrows(IllegalArgumentException.class, () -> JobStatusFilter.fromParam("CLOSED"));
	}

	@Test
	void jobTypeSpellingsCollapseToOneKey() {
		String expected = JobPostSpecifications.normalizeJobType("full-time");

		assertEquals(expected, JobPostSpecifications.normalizeJobType("Full-Time"));
		assertEquals(expected, JobPostSpecifications.normalizeJobType("full time"));
		assertEquals(expected, JobPostSpecifications.normalizeJobType("Full-time"));
		assertEquals(expected, JobPostSpecifications.normalizeJobType(" full_time "));
	}

	@Test
	void distinctJobTypesKeepDistinctKeys() {
		assertEquals("parttime", JobPostSpecifications.normalizeJobType("Part-Time"));
		assertEquals("contract", JobPostSpecifications.normalizeJobType("Contract"));
	}

	@Test
	void normalizeJobTypeIgnoresValuesWithoutContent() {
		assertNull(JobPostSpecifications.normalizeJobType(null));
		assertNull(JobPostSpecifications.normalizeJobType("   "));
		assertNull(JobPostSpecifications.normalizeJobType("-_"));
	}
}
