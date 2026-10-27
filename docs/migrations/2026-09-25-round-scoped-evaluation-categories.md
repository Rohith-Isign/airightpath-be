# Round-scoped evaluation categories and interview templates

Date: 2026-09-25

## Context

Evaluation categories were keyed by job only, so both rounds of a job were
graded against one list. An L3 behavioural round was therefore scored on
`Programming`, for questions it never asked, and an L2 on `Ownership` for the
same reason — the grader produces a number for every configured category
regardless of what was discussed.

`evaluation_category` now carries an `interview_round` column, and a new
`interview_template` table holds the per-round question budget and difficulty
that used to be global `application.yml` settings.

## What Hibernate does on its own

`spring.jpa.hibernate.ddl-auto: update` is enough for most of this. It will:

- add `interview_round` to `evaluation_category` (nullable — existing rows stay
  null, which resolves as "the shared list for every round", so nothing changes
  for a job configured before this)
- create `interview_template`

**It will not replace the existing unique constraint.** `update` adds columns
and tables; it does not drop or alter constraints. `evaluation_category` keeps
its old `(job_prefix, category_name)` uniqueness, which means a job cannot have
`Communication` for L2 *and* `Communication` for L3 — the second insert fails
with a duplicate key, and the console reports a save error that says nothing
about rounds.

## SQL

Run once per environment, after deploying the backend. Find the existing
constraint's name first — it is auto-generated, so it differs per database:

```sql
SELECT CONSTRAINT_NAME
FROM information_schema.TABLE_CONSTRAINTS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'evaluation_category'
  AND CONSTRAINT_TYPE = 'UNIQUE';
```

Then swap it, substituting the name returned above:

```sql
ALTER TABLE evaluation_category DROP INDEX `<constraint_name_from_above>`;

ALTER TABLE evaluation_category
  ADD CONSTRAINT uk_eval_category_job_round_name
  UNIQUE (job_prefix, interview_round, category_name);
```

## Note on NULL

MySQL treats NULLs as distinct in a unique index, so the new constraint does not
stop two shared (round-null) rows with the same category name. It is not a
regression in practice: existing shared rows are already unique by name under
the old constraint, and the save endpoint replaces a round's list wholesale
rather than inserting row by row. Worth knowing before relying on the constraint
as the only guard.

## Verification

1. A job with existing categories and no round set: open the console, confirm
   both rounds still show that list and interviews still score against it.
2. Save a different list for L3 only. Confirm L2 is untouched — the save is
   scoped to the round being edited, and a bug here would wipe the other round.
3. Confirm a category name can now exist for both rounds at once. If this fails
   with a duplicate key, the SQL above has not been run on that environment.

## Rollback

The column and table are additive and unread by the previous build, so rolling
the backend back needs no schema change. If the constraint must be restored:

```sql
ALTER TABLE evaluation_category DROP INDEX uk_eval_category_job_round_name;
-- Only possible once any duplicate (job_prefix, category_name) pairs created
-- under the new scheme have been removed.
ALTER TABLE evaluation_category
  ADD CONSTRAINT uk_eval_category_job_name UNIQUE (job_prefix, category_name);
```
