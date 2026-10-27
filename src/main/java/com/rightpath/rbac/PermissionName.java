package com.rightpath.rbac;

/**
 * Canonical permissions for method-level authorization.
 *
 * Keep names stable once used in @PreAuthorize.
 */
public enum PermissionName {
    // User / profile
    USER_READ,
    USER_UPDATE,
    USER_LIST,
    USER_ACTIVATE,
    USER_DEACTIVATE,

    /**
     * Granting and revoking roles, and creating staff accounts.
     *
     * <p>Separate from {@link #USER_UPDATE} because that one means "may edit a
     * profile" and is held by every candidate so they can maintain their own.
     * Role assignment was guarded by it, which made every signed-in candidate
     * able to grant themselves SUPER_ADMIN. Changing who may assign roles is
     * therefore not a tightening of an existing rule — it is closing a hole.</p>
     *
     * <p>Seeded to SUPER_ADMIN only. ADMIN cannot hold it: an admin who can mint
     * super admins is a super admin with extra steps.</p>
     */
    ROLE_MANAGE,

    // Resume
    RESUME_UPLOAD,
    RESUME_UPDATE,
    RESUME_VIEW,
    RESUME_VIEW_ALL,

    // ATS
    ATS_UPLOAD_SINGLE,
    ATS_UPLOAD_MULTI,

    // Assessments
    ASSESSMENT_UPLOAD,
    ASSESSMENT_ASSIGN,
    ASSESSMENT_SUBMIT,
    ASSESSMENT_RESULT_SUBMIT,

    // Questions
    QUESTION_GENERATE,
    CODING_QUESTION_GENERATE,

    // Job posts & applications
    JOB_POST_CREATE,
    JOB_POST_UPDATE,
    JOB_POST_DELETE,
    JOB_POST_READ,
    JOB_APPLY,
    JOB_APPLICATION_READ_ALL,

    // Interview
    INTERVIEW_ASSIGN,
    INTERVIEW_START,
    INTERVIEW_ANSWER,
    /**
     * Change a finished interview's result, and record why.
     *
     * Separate from INTERVIEW_ASSIGN, which is booking and reading. Overturning
     * an AI decision is the one action here that changes a hiring outcome after
     * the fact, and it should be grantable to the people who sign off on those
     * without also handing them scheduling.
     */
    INTERVIEW_REVIEW,
    /**
     * Remove a finished interview's result from the results list.
     *
     * Separate again from INTERVIEW_REVIEW: overturning a result argues with
     * it in public, removing one takes it off the screen people decide from.
     * Removal is soft and audited, but it is still the most destructive thing
     * available here and should be grantable on its own.
     */
    INTERVIEW_RESULT_DELETE,

    // Compiler
    COMPILER_RUN,
    COMPILER_RESULTS_READ,
    
    ATS_READ,
    ASSESSMENT_READ,
    JOB_WRITE,
    INTERVIEW_WRITE,
    QUESTION_WRITE
}
