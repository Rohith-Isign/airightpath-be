package com.rightpath.service.impl;

import java.util.List;

import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.service.InterviewLevelProgression;

/** Test access to the package-private plan check, for tests in other packages. */
public final class VoiceInterviewServiceImplPlanAccess {

    private VoiceInterviewServiceImplPlanAccess() {
    }

    public static String mismatch(InterviewLevelProgression.Expected expected, ParsedInterviewReply reply,
            List<String> required) {
        return VoiceInterviewServiceImpl.planMismatch(expected, reply, required);
    }
}
