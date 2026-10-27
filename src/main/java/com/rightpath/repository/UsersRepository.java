package com.rightpath.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.rightpath.dto.UsersDto;
import com.rightpath.entity.Users;
import com.rightpath.rbac.RoleName;

@Repository
public interface UsersRepository extends JpaRepository<Users, String> {

	/**
	 * Finds a user by their email.
	 *
	 * @param email The email of the user to be retrieved.
	 * @return An `Optional` containing the user if found, or empty if no user
	 *         exists with the specified email.
	 */
	Optional<Users> findByEmail(String email);
	
	

	/**
	 * Soft deletes a user by updating their `enabled` status to `false`.
	 *
	 * @param email The email of the user to be disabled.
	 */
	@Transactional
	@Modifying
	@Query("update Users e set e.enabled = false where e.email = :email")
	public void deleteByEmail(@Param("email") String email);

	/**
	 * Activates a user by updating their `enabled` status to `true`.
	 *
	 * @param email The email of the user to be enabled.
	 */
	@Transactional
	@Modifying
	@Query("update Users e set e.enabled = true where e.email = :email")
	public void activeByEmail(@Param("email") String email);

	/**
	 * One page of users holding none of the given roles through an active
	 * assignment — the candidate list, where the excluded roles are the staff ones.
	 *
	 * <p>A correlated {@code NOT EXISTS}, not a subquery membership test. The old
	 * form materialised the subquery and, together with {@code DISTINCT} and an
	 * {@code ORDER BY} on email, made MySQL sort the joined result — which failed
	 * outright with <em>1038 Out of sort memory</em> on barely a hundred users.
	 * {@code EXISTS} short-circuits per row against
	 * {@code idx_user_roles_email_active}, and only the page is sorted.</p>
	 *
	 * <p>{@code DISTINCT} went with it. It was there to collapse the duplicate
	 * rows the old join produced, and collapsing them is what forced the sort; a
	 * per-row predicate cannot duplicate the outer row in the first place.</p>
	 *
	 * <p>Ordering comes from the {@link Pageable} so the caller states it once.
	 * An unordered paginated query is free to show one user on two pages and
	 * another on none.</p>
	 *
	 * @param roleNames roles that disqualify a user; an empty collection returns everyone
	 * @param pageable  page, size and sort — sort by {@code email} for determinism
	 * @return the requested page, plus the total count
	 */
	@Query(value = """
			SELECT new com.rightpath.dto.UsersDto(
			    u.email, u.firstName, u.lastName, u.enabled,
			    u.mobileNumber, u.alternativeMobileNumber)
			FROM Users u
			WHERE NOT EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name IN :roleNames)
			""",
			countQuery = """
			SELECT COUNT(u) FROM Users u
			WHERE NOT EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name IN :roleNames)
			""")
	Page<UsersDto> findPageExcludingActiveRoleIn(@Param("roleNames") Collection<RoleName> roleNames,
			Pageable pageable);

	/**
	 * One page of users holding any of the given roles — the staff list.
	 *
	 * <p>The exact complement of
	 * {@link #findPageExcludingActiveRoleIn(Collection, Pageable)}: give both the
	 * same roles and every account falls in exactly one of the two lists. Same
	 * {@code EXISTS} rewrite for the same reason, and {@code active = true} so a
	 * revoked assignment does not keep someone on the staff list.</p>
	 *
	 * @param roleNames roles to match; an empty collection returns nothing
	 * @param pageable  page, size and sort — sort by {@code email} for determinism
	 * @return the requested page, plus the total count
	 */
	@Query(value = """
			SELECT new com.rightpath.dto.UsersDto(
			    u.email, u.firstName, u.lastName, u.enabled,
			    u.mobileNumber, u.alternativeMobileNumber)
			FROM Users u
			WHERE EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name IN :roleNames)
			""",
			countQuery = """
			SELECT COUNT(u) FROM Users u
			WHERE EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name IN :roleNames)
			""")
	Page<UsersDto> findPageByActiveRoleIn(@Param("roleNames") Collection<RoleName> roleNames, Pageable pageable);

	/**
	 * One page of the whole roster, optionally narrowed by a name/email search.
	 *
	 * <p>The two role-scoped queries above answer "staff" and "candidates"
	 * separately, which is right for their own endpoints and wrong for a screen
	 * that shows one combined, sorted, filterable list — merging two independent
	 * pagers cannot produce stable page boundaries.</p>
	 *
	 * @param search lower-cased {@code %term%}, or null for no text filter
	 */
	@Query(value = """
			SELECT new com.rightpath.dto.UsersDto(
			    u.email, u.firstName, u.lastName, u.enabled,
			    u.mobileNumber, u.alternativeMobileNumber)
			FROM Users u
			WHERE (:search IS NULL
			       OR LOWER(u.email) LIKE :search
			       OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE :search)
			""",
			countQuery = """
			SELECT COUNT(u) FROM Users u
			WHERE (:search IS NULL
			       OR LOWER(u.email) LIKE :search
			       OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE :search)
			""")
	Page<UsersDto> findDirectory(@Param("search") String search, Pageable pageable);

	/**
	 * One page of the roster holding a specific role, optionally searched.
	 *
	 * <p>Same {@code EXISTS} shape as the staff query — a per-row predicate
	 * against {@code idx_user_roles_email_active}, no {@code DISTINCT}, so no
	 * sort of a joined result.</p>
	 */
	@Query(value = """
			SELECT new com.rightpath.dto.UsersDto(
			    u.email, u.firstName, u.lastName, u.enabled,
			    u.mobileNumber, u.alternativeMobileNumber)
			FROM Users u
			WHERE EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name = :role)
			  AND (:search IS NULL
			       OR LOWER(u.email) LIKE :search
			       OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE :search)
			""",
			countQuery = """
			SELECT COUNT(u) FROM Users u
			WHERE EXISTS (
			    SELECT ur.id FROM UserRole ur
			    WHERE ur.user.email = u.email
			      AND ur.active = true
			      AND ur.role.name = :role)
			  AND (:search IS NULL
			       OR LOWER(u.email) LIKE :search
			       OR LOWER(CONCAT(u.firstName, ' ', u.lastName)) LIKE :search)
			""")
	Page<UsersDto> findDirectoryByRole(@Param("role") RoleName role, @Param("search") String search,
			Pageable pageable);

	/**
	 * Updates a user's password by their email.
	 *
	 * @param email       The email of the user whose password is being updated.
	 * @param newPassword The new password to set.
	 */
	@Transactional
	@Modifying
	@Query("update Users e set e.password = :newPassword where e.email = :email")
	void updatePasswordByEmail(@Param("email") String email, @Param("newPassword") String newPassword);

	boolean existsByEmail(String email);
	boolean existsByMobileNumber(String mobileNumber);
	
	Optional<Users> findByMobileNumber(String mobile);
}
