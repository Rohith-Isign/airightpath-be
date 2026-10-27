package com.rightpath.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.rightpath.entity.JobPost;
import com.rightpath.enums.JobStatusFilter;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;

/**
 * Reusable {@link Specification}s for filtering {@link JobPost} rows.
 *
 * <p>The same predicates drive the listing page and the status-count queries, so
 * "Active (12)" can never disagree with the rows a {@code status=ACTIVE} page
 * actually returns.</p>
 *
 * <p>Each factory method returns {@code null} when the corresponding filter is
 * absent; {@link #combine(Specification...)} drops those, which keeps the
 * generated SQL free of {@code (:param is null or …)} no-op clauses.</p>
 */
public final class JobPostSpecifications {

    /** Entity property holding the deadline that decides ACTIVE vs EXPIRED. */
    public static final String FIELD_APPLICATION_DEADLINE = "applicationDeadline";

    /** Entity property holding the free-text job type. */
    public static final String FIELD_JOB_TYPE = "jobType";

    /** Entity property that is null while a posting is live and set once archived. */
    public static final String FIELD_DELETED_AT = "deletedAt";

    /**
     * Columns scanned by the free-text {@code search} term. Kept in one place so
     * the listing query and any future export stay in sync.
     */
    private static final String[] SEARCHABLE_FIELDS = {
            "jobTitle", "companyName", "keySkills", "location", "jobPrefix"
    };

    /**
     * Separators stripped before comparing job types, so free-text values such as
     * {@code "Full-Time"}, {@code "full time"} and {@code "full_time"} all collapse
     * to the same key.
     */
    private static final String[] JOB_TYPE_SEPARATORS = { "-", "_", " " };

    /**
     * Escape character for {@code LIKE} patterns. {@code !} is used instead of the
     * conventional backslash to avoid double-escaping through JPQL/JDBC layers.
     */
    private static final char LIKE_ESCAPE = '!';

    private JobPostSpecifications() {
    }

    /**
     * Case-insensitive substring match across {@link #SEARCHABLE_FIELDS}.
     *
     * <p>A term such as {@code "dev"} therefore matches both the prefix
     * {@code FE-DEV-2026-005} and the title "Junior Software Developer".
     * {@code %} and {@code _} typed by the user are escaped so they match
     * literally instead of acting as wildcards.</p>
     *
     * @param rawSearch the user's term; {@code null}/blank disables the filter
     * @return the specification, or {@code null} when there is nothing to match
     */
    public static Specification<JobPost> matchesSearch(String rawSearch) {
        if (rawSearch == null || rawSearch.isBlank()) {
            return null;
        }
        String pattern = "%" + escapeLikeWildcards(rawSearch.trim().toLowerCase()) + "%";

        return (root, query, cb) -> {
            List<Predicate> anyField = new ArrayList<>(SEARCHABLE_FIELDS.length);
            for (String field : SEARCHABLE_FIELDS) {
                Path<String> path = root.get(field);
                anyField.add(cb.like(cb.lower(path), pattern, LIKE_ESCAPE));
            }
            return cb.or(anyField.toArray(new Predicate[0]));
        };
    }

    /**
     * Matches {@code jobType} ignoring case and separators, because the column is
     * free text: a request for {@code full-time} must find rows stored as
     * {@code Full-Time}, {@code full time} or {@code Full-time}.
     *
     * @param rawJobType the requested type; {@code null}/blank (or punctuation
     *                   only) disables the filter
     * @return the specification, or {@code null} when there is nothing to match
     */
    public static Specification<JobPost> matchesJobType(String rawJobType) {
        String normalized = normalizeJobType(rawJobType);
        if (normalized == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(normalizedJobType(cb, root.get(FIELD_JOB_TYPE)), normalized);
    }

    /**
     * Restricts rows to a deadline bucket.
     *
     * <p>{@code ACTIVE} keeps rows whose deadline is {@code today} or later plus
     * rows with no deadline; {@code EXPIRED} keeps only rows strictly before
     * {@code today} (a {@code NULL} deadline never satisfies that comparison, so
     * undated postings are consistently treated as active). {@code ALL} adds no
     * predicate.</p>
     *
     * @param status the requested bucket
     * @param today  "today" as resolved in the business timezone by the caller —
     *               passed in rather than read from the database so the boundary
     *               does not shift with the DB session's timezone
     * @return the specification, or {@code null} for {@link JobStatusFilter#ALL}
     */
    public static Specification<JobPost> hasStatus(JobStatusFilter status, LocalDate today) {
        if (status == null || status == JobStatusFilter.ALL) {
            return null;
        }
        if (status == JobStatusFilter.ACTIVE) {
            return (root, query, cb) -> cb.or(
                    cb.isNull(root.get(FIELD_APPLICATION_DEADLINE)),
                    cb.greaterThanOrEqualTo(root.get(FIELD_APPLICATION_DEADLINE), today));
        }
        return (root, query, cb) -> cb.lessThan(root.get(FIELD_APPLICATION_DEADLINE), today);
    }

    /**
     * Excludes archived (soft-deleted) postings.
     *
     * <p>Applied to every listing and count, so a deleted job disappears from the admin
     * board, the candidate job list and the status/job-type dropdowns at once. This is
     * orthogonal to {@link JobStatusFilter}: even {@code status=ALL} means "all live
     * postings", never archived ones.</p>
     *
     * @return a specification matching only live postings
     */
    public static Specification<JobPost> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get(FIELD_DELETED_AT));
    }

    /**
     * ANDs the given specifications, ignoring {@code null} entries.
     *
     * @param specs specifications to combine, any of which may be {@code null}
     * @return the combined specification, or {@code null} if all were {@code null}
     */
    @SafeVarargs
    public static Specification<JobPost> combine(Specification<JobPost>... specs) {
        List<Specification<JobPost>> present = new ArrayList<>(specs.length);
        for (Specification<JobPost> spec : specs) {
            if (spec != null) {
                present.add(spec);
            }
        }
        return present.isEmpty() ? null : Specification.allOf(present);
    }

    /**
     * Collapses a free-text job type to a comparison key: lower-cased with
     * hyphens, underscores and spaces removed.
     *
     * @param rawJobType raw value from a request or a database row
     * @return the key, or {@code null} if the value is absent or has no
     *         alphanumeric content once separators are stripped
     */
    public static String normalizeJobType(String rawJobType) {
        if (rawJobType == null) {
            return null;
        }
        String normalized = rawJobType.trim().toLowerCase();
        for (String separator : JOB_TYPE_SEPARATORS) {
            normalized = normalized.replace(separator, "");
        }
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * SQL-side counterpart of {@link #normalizeJobType(String)}, so the same
     * collapsing is applied to stored values during comparison.
     */
    private static Expression<String> normalizedJobType(CriteriaBuilder cb, Path<String> path) {
        Expression<String> expression = cb.lower(path);
        for (String separator : JOB_TYPE_SEPARATORS) {
            expression = cb.function("replace", String.class,
                    expression, cb.literal(separator), cb.literal(""));
        }
        return expression;
    }

    /** Neutralises {@code %}, {@code _} and the escape character itself. */
    private static String escapeLikeWildcards(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 4);
        for (char character : value.toCharArray()) {
            if (character == '%' || character == '_' || character == LIKE_ESCAPE) {
                escaped.append(LIKE_ESCAPE);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }
}
