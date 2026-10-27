package com.rightpath.dto.voice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoiceAnswerRequest {

    private String transcript;
    private List<WordTimestamp> wordTimestamps;
    private boolean skipped;
    private String codeContent;
    private String codeLanguage;

    /**
     * What the code printed when the candidate ran it — compiler errors included.
     *
     * <p>The editor has had a Compile &amp; Run button all along, but its output
     * stayed on the candidate's screen: only the source was ever submitted. So
     * the interviewer judged code it had never seen execute and could not tell
     * working code from code that does not compile. Null means they never
     * pressed Run, which is itself worth knowing.</p>
     */
    private String codeOutput;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WordTimestamp {
        private String word;
        private double start;
        private double end;
    }
}
