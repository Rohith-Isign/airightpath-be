package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CodingExamplesTest {

    @Test
    void aCodingQuestionWithExamplesPasses() {
        assertTrue(VoiceInterviewServiceImpl.hasExamples(
                "[CODING] Write a function that doubles each number. Example 1: Input: [1,2] Output: [2,4]"));
    }

    @Test
    void anInputAndOutputWithoutTheWordExampleAlsoPasses() {
        assertTrue(VoiceInterviewServiceImpl.hasExamples(
                "[CODING] Double each number. Input: [1,2]. Output: [2,4]."));
    }

    @Test
    void aBareCodingQuestionIsCaughtSoExamplesCanBeAsked() {
        assertFalse(VoiceInterviewServiceImpl.hasExamples(
                "[CODING] Write a Python function that takes a list of integers and returns a new list with each integer doubled."));
    }
}
