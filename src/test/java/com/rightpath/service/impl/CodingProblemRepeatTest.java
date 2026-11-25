package com.rightpath.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A coding problem is asked once. These pin how a repeat is recognised: by the problem statement, ignoring the tag,
 * the task type line and the examples, so a reworded preamble does not hide it and a different problem is not caught.
 */
class CodingProblemRepeatTest {

    private static final String MAX_DIFF = "[CODING] Write a function that takes an array of integers and returns the "
            + "maximum difference between any two elements such that the larger element appears after the smaller "
            + "element.\nExample 1: Input: [2, 3, 10, 6, 4, 8, 1] Output: 8\nExample 2: Input: [7, 9, 5, 6, 3, 2] "
            + "Output: 2";

    private static double similarity(String a, String b) {
        return VoiceInterviewServiceImpl.similarity(
                VoiceInterviewServiceImpl.problemWords(a), VoiceInterviewServiceImpl.problemWords(b));
    }

    @Test
    @DisplayName("the same problem asked again is a repeat, even with a task type line and other examples")
    void sameProblemIsRepeat() {
        String again = "[CODING]\nTask type: WRITE THE CODE\nWrite a function that takes an array of integers and "
                + "returns the maximum difference between any two elements such that the larger element appears "
                + "after the smaller element.\nExample 1: Input: [1, 5] Output: 4\nExample 2: Input: [5, 1] Output: 0";
        assertThat(similarity(MAX_DIFF, again)).isGreaterThanOrEqualTo(0.8);
    }

    @Test
    @DisplayName("a different problem is not a repeat")
    void differentProblemIsNot() {
        String other = "[CODING] Write a function that checks whether a string is a palindrome, ignoring spaces and "
                + "case.\nExample 1: Input: \"Race car\" Output: true\nExample 2: Input: \"hello\" Output: false";
        assertThat(similarity(MAX_DIFF, other)).isLessThan(0.8);
    }
}
