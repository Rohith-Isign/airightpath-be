package com.rightpath.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.VoiceEvaluationResult;

/** The rules that decide whether an answer counts: what language it was in, and what it takes to pass. */
class AnswerQualityRulesTest {

    @Test
    @DisplayName("Telugu speech is recognised as not English")
    void teluguIsForeign() {
        assertThat(AudioTranscriptionService.looksForeign("telugu", "నా పేరు సాయి, నేను ఇంజనీరింగ్ పూర్తి చేశాను")).isTrue();
    }

    @Test
    @DisplayName("English, or English misheard as another language in Latin letters, is not flagged")
    void englishIsNotForeign() {
        assertThat(AudioTranscriptionService.looksForeign("english", "My name is Sai")).isFalse();
        // Accented English sometimes comes back labelled as another language but still in Latin letters.
        assertThat(AudioTranscriptionService.looksForeign("hindi", "my name is sai and i worked on spring boot"))
                .isFalse();
        // Too little to judge.
        assertThat(AudioTranscriptionService.looksForeign("telugu", "హాయ్")).isFalse();
    }

    @Test
    @DisplayName("a pass needs the score: the scope where there is one, otherwise the overall")
    void earnedOutOf100() {
        VoiceEvaluationResult withScope = new VoiceEvaluationResult();
        withScope.setOverallScore(4.0);
        withScope.setScopeScore(40.0);
        assertThat(InterviewEvaluationService.earnedOutOf100(withScope)).isEqualTo(40.0);

        VoiceEvaluationResult withoutScope = new VoiceEvaluationResult();
        withoutScope.setOverallScore(3.5);
        assertThat(InterviewEvaluationService.earnedOutOf100(withoutScope)).isEqualTo(35.0);
    }
}
