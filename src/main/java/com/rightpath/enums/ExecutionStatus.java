package com.rightpath.enums;

/**
 * What happened to one run of a candidate's code.
 *
 * <p>These are the states an exam UI has to tell apart. Code that fails to
 * compile, code that crashes, code that never finishes and code that simply
 * prints the wrong answer are four different messages to a candidate under time
 * pressure — lumping them together as "Runtime Error" tells them nothing they
 * can act on.</p>
 */
public enum ExecutionStatus {

    /** Ran to completion and matched the expected output. */
    PASSED,

    /** Ran to completion, but printed something other than the expected output. */
    WRONG_ANSWER,

    /** Never ran: the source did not compile (syntax error, type error, ...). */
    COMPILE_ERROR,

    /** Started, then crashed — index out of bounds, stack overflow, segfault, ... */
    RUNTIME_ERROR,

    /** Exceeded the time allowed for a single run, usually an unterminated loop. */
    TIMEOUT,

    /** Printed more than the capture limit, usually a runaway print inside a loop. */
    OUTPUT_LIMIT_EXCEEDED,

    /** Not attempted, because the submission's overall time budget was already spent. */
    NOT_RUN,

    /** The platform failed, not the candidate's code — a missing toolchain, say. */
    INTERNAL_ERROR
}
