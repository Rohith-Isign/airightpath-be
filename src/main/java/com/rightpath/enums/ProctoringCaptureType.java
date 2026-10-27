package com.rightpath.enums;

/**
 * Kinds of pre-exam proctoring artefact captured on the instructions screen.
 *
 * <p>Both are taken before the exam clock starts, and whether either is asked for
 * is an environment decision on the client rather than the candidate's. Either
 * way the backend stores whatever arrives — an attempt with no captures at all
 * is an ordinary state, not a fault.</p>
 */
public enum ProctoringCaptureType {

    /** One face photo per assessment attempt, used to confirm who sat the exam. */
    IDENTITY_PHOTO,

    /** One frame of a guided room sweep; ordered by frame index. */
    ROOM_SCAN_FRAME
}
