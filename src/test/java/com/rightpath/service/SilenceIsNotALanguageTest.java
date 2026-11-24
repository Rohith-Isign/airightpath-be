package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A candidate in a silent room was told their answer "sounded like Telugu": on silence the language check invents a
 * sentence in another script. A chunk's language now counts only when its English transcript held real words.
 */
class SilenceIsNotALanguageTest {

    @Test
    void silenceAndStockFillersAreNotSpeech() {
        assertFalse(AudioTranscriptionService.hasSpeech(null));
        assertFalse(AudioTranscriptionService.hasSpeech(""));
        assertFalse(AudioTranscriptionService.hasSpeech("   "));
        assertFalse(AudioTranscriptionService.hasSpeech("you"));
        assertFalse(AudioTranscriptionService.hasSpeech("Thank you."));
        assertFalse(AudioTranscriptionService.hasSpeech("Thanks for watching!"));
        assertFalse(AudioTranscriptionService.hasSpeech("..."));
    }

    @Test
    void anActualAnswerIsSpeech() {
        assertTrue(AudioTranscriptionService.hasSpeech("My name is Praveen"));
        assertTrue(AudioTranscriptionService.hasSpeech("Dependency injection"));
        assertTrue(AudioTranscriptionService.hasSpeech("H E L L O"));
    }

    @Test
    void foreignTextStillNeedsTheTranscriptToHaveWords() {
        // The check that flags Telugu is unchanged; it is only no longer reached for an empty chunk.
        assertTrue(AudioTranscriptionService.looksForeign("telugu", "నా పేరు ప్రవీణ్ నేను ఇంజనీర్"));
        assertFalse(AudioTranscriptionService.looksForeign("english", "my name is Praveen"));
    }
}
