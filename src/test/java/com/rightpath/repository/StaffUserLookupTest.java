package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.dto.UsersDto;
import com.rightpath.rbac.RoleName;

/**
 * The two role-filtered user queries, against the real MySQL schema.
 *
 * <p>These replaced a pair that used {@code email NOT IN (subquery)} with
 * {@code DISTINCT} and {@code ORDER BY email}. That combination made MySQL sort
 * the joined result and failed outright with <em>1038 Out of sort memory</em> on
 * roughly a hundred users — so "the query runs at all" is a real assertion here,
 * not a formality.</p>
 *
 * <p>Read-only, asserting only properties that hold for any database contents,
 * so it is safe against a shared dev database.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StaffUserLookupTest {

    private static final List<RoleName> STAFF = List.of(RoleName.SUPER_ADMIN, RoleName.ADMIN);

    /** Email ascending, as the service uses. Pages are meaningless unsorted. */
    private static final Sort EMAIL_ASC = Sort.by(Sort.Direction.ASC, "email");
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 500, EMAIL_ASC);

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void bothQueriesRunWithoutExhaustingSortMemory() {
        // The regression guard. If the EXISTS rewrite is ever reverted to
        // NOT IN + DISTINCT, these two calls throw instead of returning.
        Page<UsersDto> staff = usersRepository.findPageByActiveRoleIn(STAFF, FIRST_PAGE);
        Page<UsersDto> candidates = usersRepository.findPageExcludingActiveRoleIn(STAFF, FIRST_PAGE);

        assertTrue(staff.getTotalElements() >= 0);
        assertTrue(candidates.getTotalElements() >= 0);
    }

    @Test
    void returnsNothingForAnEmptyRoleSet() {
        assertTrue(usersRepository.findPageByActiveRoleIn(List.of(), FIRST_PAGE).isEmpty());
    }

    @Test
    void everyStaffUserActuallyHoldsAStaffRole() {
        List<UsersDto> staff = usersRepository.findPageByActiveRoleIn(STAFF, FIRST_PAGE).getContent();

        for (UsersDto user : staff) {
            Set<RoleName> held = userRoleRepository.findByUser_EmailAndActiveTrue(user.getEmail()).stream()
                    .map(assignment -> assignment.getRole().getName())
                    .collect(Collectors.toSet());
            assertTrue(
                    held.contains(RoleName.ADMIN) || held.contains(RoleName.SUPER_ADMIN),
                    () -> user.getEmail() + " is listed as staff but holds " + held);
        }
    }

    @Test
    void listsEachStaffUserExactlyOnce() {
        // DISTINCT used to do this, and collapsing the duplicates it created was
        // what forced the fatal sort. EXISTS is a per-row predicate that cannot
        // duplicate the outer row — this is the assertion that lets DISTINCT
        // stay gone.
        List<UsersDto> staff = usersRepository.findPageByActiveRoleIn(STAFF, FIRST_PAGE).getContent();
        Set<String> emails = staff.stream().map(UsersDto::getEmail).collect(Collectors.toSet());

        assertEquals(emails.size(), staff.size(), "the staff list contains a duplicate");
    }

    @Test
    void staffAndCandidateListsAreExactComplements() {
        // The admin screen merges these two, so an account on both would be
        // administered from two places, and one on neither would be invisible.
        Set<String> staff = usersRepository.findPageByActiveRoleIn(STAFF, FIRST_PAGE)
                .getContent().stream().map(UsersDto::getEmail).collect(Collectors.toSet());
        Set<String> candidates = usersRepository.findPageExcludingActiveRoleIn(STAFF, FIRST_PAGE)
                .getContent().stream().map(UsersDto::getEmail).collect(Collectors.toSet());

        Set<String> onBoth = staff.stream().filter(candidates::contains).collect(Collectors.toSet());
        assertTrue(onBoth.isEmpty(), () -> "accounts on both lists: " + onBoth);

        assertEquals(usersRepository.count(), staff.size() + candidates.size(),
                "some account is on neither list");
    }

    @Test
    void pagesAreOrderedAndDoNotRepeatOrSkipAccounts() {
        // Walk the candidate list two rows at a time and reassemble it. An
        // unordered paginated query returns one row twice and another never,
        // which only shows up once the pages are put back together.
        Pageable small = PageRequest.of(0, 2, EMAIL_ASC);
        Page<UsersDto> first = usersRepository.findPageExcludingActiveRoleIn(STAFF, small);

        List<String> walked = new ArrayList<>();
        Pageable cursor = small;
        for (int i = 0; i < first.getTotalPages(); i++) {
            usersRepository.findPageExcludingActiveRoleIn(STAFF, cursor)
                    .forEach(user -> walked.add(user.getEmail()));
            cursor = cursor.next();
        }

        assertEquals(first.getTotalElements(), walked.size(), "paging lost or repeated rows");
        assertEquals(walked.size(), Set.copyOf(walked).size(), "an account appeared on two pages");

        List<String> sorted = new ArrayList<>(walked);
        Collections.sort(sorted);
        assertEquals(sorted, walked, "pages are not in email order");
    }

    @Test
    void theCountQueryAgreesWithTheRowQuery() {
        // totalElements comes from a separate count query, so the two predicates
        // can drift apart and leave the pager claiming pages that do not exist.
        Page<UsersDto> onePerPage = usersRepository.findPageByActiveRoleIn(STAFF, PageRequest.of(0, 1, EMAIL_ASC));
        List<UsersDto> everyone = usersRepository.findPageByActiveRoleIn(STAFF, FIRST_PAGE).getContent();

        assertEquals(everyone.size(), onePerPage.getTotalElements(),
                "count query disagrees with the row query");
    }
}
