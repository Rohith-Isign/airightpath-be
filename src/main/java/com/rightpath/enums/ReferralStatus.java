package com.rightpath.enums;

/**
 * Verification state of a candidate's referral. Defaults to {@link #PENDING} at
 * apply time when referral details are supplied, and is updated later by a
 * recruiter (e.g. to {@link #VERIFIED} or {@link #REJECTED}).
 */
public enum ReferralStatus {
    PENDING,
    VERIFIED,
    REJECTED
}
