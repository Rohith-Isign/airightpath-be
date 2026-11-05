package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreetingStripperTest {

    @Test
    void aSecondGreetingIsRemovedAndTheQuestionKept() {
        String model = "Hi Praveen, welcome to the interview. I'm Sarah, and I'll be asking you some questions today. "
                + "To start off, could you please introduce yourself?";
        assertEquals("To start off, could you please introduce yourself?", GreetingStripper.strip(model));
    }

    @Test
    void aQuestionWithNoGreetingIsUntouched() {
        String model = "Could you tell me about yourself and what you have worked on?";
        assertEquals(model, GreetingStripper.strip(model));
    }

    @Test
    void aGreetingThatIsAlsoTheQuestionIsKept() {
        String model = "Hello! Could you introduce yourself?";
        assertEquals("Could you introduce yourself?", GreetingStripper.strip(model));
    }

    @Test
    void aReplyThatIsAllGreetingKeepsItsLastSentence() {
        assertEquals("Let's begin.", GreetingStripper.strip("Hello there. Welcome. Let's begin."));
    }

    @Test
    void aLaterSentenceThatMerelyStartsLikeAGreetingIsNotRemoved() {
        String model = "Tell me about a project. Welcome changes to the plan are common, how did you handle one?";
        assertEquals(model, GreetingStripper.strip(model));
    }
}
