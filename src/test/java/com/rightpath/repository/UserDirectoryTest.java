package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 * The combined, filterable roster the admin user screen reads.
 *
 * <p>Read-only and asserted only on invariants that hold for any database
 * contents, so it is safe against a shared dev database.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserDirectoryTest {

    private static final Sort EMAIL_ASC = Sort.by(Sort.Direction.ASC, "email");
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 500, EMAIL_ASC);

    @Autowired
    private UsersRepository usersRepository;

    @Test
    void unfilteredDirectoryCoversEveryAccount() {
        assertEquals(usersRepository.count(),
                usersRepository.findDirectory(null, FIRST_PAGE).getTotalElements());
    }

    @Test
    void roleFiltersPartitionTheRoster() {
        // Every account holds exactly one of the three roles in this system, so
        // the three filtered totals must add up to the whole roster. If they do
        // not, some account holds none and is invisible on every filtered view.
        long admin = usersRepository.findDirectoryByRole(RoleName.ADMIN, null, FIRST_PAGE).getTotalElements();
        long superAdmin = usersRepository.findDirectoryByRole(RoleName.SUPER_ADMIN, null, FIRST_PAGE).getTotalElements();
        long candidate = usersRepository.findDirectoryByRole(RoleName.USER, null, FIRST_PAGE).getTotalElements();

        assertEquals(usersRepository.count(), admin + superAdmin + candidate,
                "role filters do not partition the roster");
    }

    @Test
    void everyRoleFilteredRowReallyHoldsThatRole() {
        for (RoleName role : RoleName.values()) {
            Page<UsersDto> page = usersRepository.findDirectoryByRole(role, null, FIRST_PAGE);
            Set<String> expected = Set.copyOf(
                    usersRepository.findPageByActiveRoleIn(List.of(role), FIRST_PAGE)
                            .getContent().stream().map(UsersDto::getEmail).toList());
            Set<String> actual = Set.copyOf(page.getContent().stream().map(UsersDto::getEmail).toList());
            assertEquals(expected, actual, () -> "directory disagrees with the role query for " + role);
        }
    }

    @Test
    void searchNarrowsAndNeverWidens() {
        long everyone = usersRepository.findDirectory(null, FIRST_PAGE).getTotalElements();
        long searched = usersRepository.findDirectory("%a%", FIRST_PAGE).getTotalElements();
        assertTrue(searched <= everyone, "a search returned more rows than the unfiltered roster");

        // A term that cannot appear must return nothing rather than everything —
        // the null-tolerant LIKE could easily be written so a value is ignored.
        assertEquals(0, usersRepository.findDirectory("%zzz-no-such-user-zzz%", FIRST_PAGE).getTotalElements());
    }

    @Test
    void blankSearchIsNotTreatedAsAFilter() {
        // The service maps a blank box to null; this pins that null really means
        // "everyone", so an empty search box cannot silently empty the screen.
        assertEquals(usersRepository.count(),
                usersRepository.findDirectory(null, FIRST_PAGE).getTotalElements());
    }

    @Test
    void pagesAreStableAcrossTheWholeRoster() {
        Pageable small = PageRequest.of(0, 3, EMAIL_ASC);
        Page<UsersDto> first = usersRepository.findDirectory(null, small);

        List<String> walked = new ArrayList<>();
        Pageable cursor = small;
        for (int i = 0; i < first.getTotalPages(); i++) {
            usersRepository.findDirectory(null, cursor).forEach(u -> walked.add(u.getEmail()));
            cursor = cursor.next();
        }

        assertEquals(first.getTotalElements(), walked.size(), "paging lost or repeated rows");
        assertEquals(walked.size(), Set.copyOf(walked).size(), "an account appeared on two pages");
        assertEquals(walked.stream().sorted().toList(), walked, "pages are not in email order");
    }
}
