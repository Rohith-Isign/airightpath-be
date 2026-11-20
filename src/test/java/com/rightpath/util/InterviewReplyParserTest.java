package com.rightpath.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.dto.voice.ParsedInterviewReply;
import com.rightpath.enums.TurnKind;
import com.rightpath.service.InterviewConductPolicy;

/**
 * Everything the server knows about a turn comes through this parser, and
 * anything it fails to strip is read aloud to the candidate. Both sides matter:
 * the data has to come out right, and the speech has to come out clean.
 */
class InterviewReplyParserTest {

    private InterviewReplyParser parser;

    @BeforeEach
    void setUp() {
        parser = new InterviewReplyParser(new InterviewConductPolicy(new PromptInjectionGuard()));
    }

    @Test
    @DisplayName("reads score, topic and turn kind, and leaves only the speech")
    void parsesAFullTagBlock() {
        ParsedInterviewReply reply = parser.parse(
                "[SCORE:7] [TOPIC:Data Structures] [FOLLOWUP] You mentioned hash maps — "
                        + "what happens when two keys collide?");

        assertThat(reply.answerScore()).isEqualTo(7);
        assertThat(reply.topic()).isEqualTo("Data Structures");
        assertThat(reply.turnKind()).isEqualTo(TurnKind.FOLLOW_UP);
        assertThat(reply.closing()).isFalse();
        assertThat(reply.spokenText())
                .isEqualTo("You mentioned hash maps — what happens when two keys collide?");
    }

    @Test
    @DisplayName("an untagged reply is a new question, exactly as before the protocol existed")
    void untaggedReplyDegradesGracefully() {
        ParsedInterviewReply reply = parser.parse("What is a foreign key?");

        assertThat(reply.turnKind()).isEqualTo(TurnKind.NEW_QUESTION);
        assertThat(reply.hasScore()).isFalse();
        assertThat(reply.hasTopic()).isFalse();
        assertThat(reply.spokenText()).isEqualTo("What is a foreign key?");
    }

    @Test
    @DisplayName("keeps [CODING] and moves it back to the front")
    void codingTagSurvivesAndLeads() {
        // The browser only opens the editor for a tag that leads the message, so
        // stripping the tags in front of it must not leave it stranded
        // mid-sentence — the candidate would be asked to write code with nowhere
        // to write it.
        ParsedInterviewReply reply = parser.parse(
                "[SCORE:5] [TOPIC:Programming] [CODING] Write a function that reverses a string.");

        assertThat(reply.spokenText()).startsWith("[CODING] ");
        assertThat(reply.spokenText()).endsWith("Write a function that reverses a string.");
        assertThat(reply.answerScore()).isEqualTo(5);
    }

    @Test
    @DisplayName("rephrase wins when the model tags both")
    void rephraseBeatsFollowUp() {
        ParsedInterviewReply reply = parser.parse("[FOLLOWUP] [REPHRASE] Let me put that another way.");
        assertThat(reply.turnKind()).isEqualTo(TurnKind.REPHRASE);
    }

    @Test
    @DisplayName("recognises the closing marker in either spelling")
    void detectsBothCompletionSpellings() {
        // The policy asked for one spelling and the performance guidance for the
        // other, so a reply using the second closed nothing and was read out to
        // the candidate verbatim.
        assertThat(parser.parse("Thanks for your time. [INTERVIEW COMPLETE]").closing()).isTrue();
        assertThat(parser.parse("Thanks for your time. [INTERVIEW_COMPLETE]").closing()).isTrue();

        assertThat(parser.parse("Thanks for your time. [INTERVIEW_COMPLETE]").spokenText())
                .isEqualTo("Thanks for your time.");
    }

    @Test
    @DisplayName("a score outside 0-10 is discarded rather than trusted")
    void rejectsOutOfRangeScore() {
        assertThat(parser.parse("[SCORE:99] Next question").answerScore()).isNull();
    }

    @Test
    @DisplayName("tolerates the casing and spacing a model actually produces")
    void toleratesLooseFormatting() {
        ParsedInterviewReply reply = parser.parse(
                "[score: 8] [Topic: Problem Solving ] [follow-up] And why that approach?");

        assertThat(reply.answerScore()).isEqualTo(8);
        assertThat(reply.topic()).isEqualTo("Problem Solving");
        assertThat(reply.turnKind()).isEqualTo(TurnKind.FOLLOW_UP);
        assertThat(reply.spokenText()).isEqualTo("And why that approach?");
    }

    @Test
    @DisplayName("an empty reply does not blow up the turn")
    void handlesEmptyReply() {
        ParsedInterviewReply reply = parser.parse(null);

        assertThat(reply.spokenText()).isEmpty();
        assertThat(reply.turnKind()).isEqualTo(TurnKind.NEW_QUESTION);
        assertThat(reply.closing()).isFalse();
    }

    @Test
    void anUntaggedQuestionThatDoesNotAskForCodeNeverOpensTheEditor() {
        // Guessing from loose wording opened the editor for spoken questions ("walk me through how you would solve a
        // problem in a component"), so only an instruction to write code is recognised (see the test below).
        var parsed = parser.parse("[SCORE:6] [TOPIC:Programming] Walk me through how you would solve a problem in a component.");

        org.junit.jupiter.api.Assertions.assertFalse(parsed.spokenText().contains("[CODING]"), parsed.spokenText());
    }

    @Test
    void aQuestionToExplainSomethingDoesNotOpenTheEditor() {
        var parsed = parser.parse("[SCORE:6] [TOPIC:Java] Can you explain how a HashMap handles a collision in the code?");

        org.junit.jupiter.api.Assertions.assertFalse(parsed.spokenText().contains("[CODING]"), parsed.spokenText());
    }

    @Test
    void aTheoryTaggedQuestionDoesNotOpenTheEditor() {
        var parsed = parser.parse("[THEORY] What is the difference between an interface and an abstract class?");

        org.junit.jupiter.api.Assertions.assertFalse(parsed.spokenText().contains("[CODING]"), parsed.spokenText());
    }

    @Test
    void aTaggedCodingQuestionIsNotTaggedTwice() {
        var parsed = parser.parse("[SCORE:7] [TOPIC:Programming] [CODING] Write a function that reverses a linked list.");

        String spoken = parsed.spokenText();
        org.junit.jupiter.api.Assertions.assertEquals(spoken.indexOf("[CODING]"), spoken.lastIndexOf("[CODING]"));
        org.junit.jupiter.api.Assertions.assertTrue(spoken.startsWith("[CODING]"), spoken);
    }

    @Test
    @DisplayName("a plain request to write code opens the editor even when the model forgot the tag")
    void anUntaggedWriteCodeRequestGetsTheEditor() {
        ParsedInterviewReply reply = parser.parse(
                "That's a solid explanation. Now, let's move on to a coding question. "
                        + "Write a Python function that calculates the mean of a list.");

        org.junit.jupiter.api.Assertions.assertTrue(reply.spokenText().startsWith("[CODING]"), reply.spokenText());
    }

    @Test
    @DisplayName("a spoken question, or one tagged as theory, never opens the editor")
    void spokenQuestionsKeepTheEditorClosed() {
        org.junit.jupiter.api.Assertions.assertFalse(
                parser.parse("Could you explain what a function is and why we write them?").spokenText()
                        .startsWith("[CODING]"));
        org.junit.jupiter.api.Assertions.assertFalse(
                parser.parse("[THEORY] Explain how you would write a class that is thread safe.").spokenText()
                        .startsWith("[CODING]"));
    }

    @Test
    @DisplayName("reads the level and keeps it out of the speech, with [CODING] still leading")
    void parsesTheLevelTag() {
        ParsedInterviewReply reply = parser.parse(
                "[SCORE:7] [TOPIC:Programming] [LEVEL:intermediate] [CODING] Write the logic for a palindrome check.");

        assertThat(reply.level()).isEqualTo("INTERMEDIATE");
        assertThat(reply.hasLevel()).isTrue();
        assertThat(reply.spokenText()).isEqualTo("[CODING] Write the logic for a palindrome check.");
    }

    @Test
    @DisplayName("a reply with no level, or an unknown one, has none and the tag text is not left in the speech")
    void missingOrUnknownLevelIsNull() {
        assertThat(parser.parse("What is a foreign key?").level()).isNull();
        ParsedInterviewReply reply = parser.parse("[LEVEL:GURU] What is a foreign key?");
        assertThat(reply.level()).isNull();
    }

    @Test
    @DisplayName("[MISMATCH] is read and never spoken; a reply without it flags nothing")
    void parsesMismatch() {
        ParsedInterviewReply reply = parser.parse(
                "[SCORE:0] [TOPIC:Programming] [LEVEL:BASIC] [MISMATCH] That code checks for primes, not palindromes. "
                        + "Let's move on.");
        assertThat(reply.codeMismatch()).isTrue();
        assertThat(reply.spokenText()).doesNotContain("MISMATCH").startsWith("That code checks");
        assertThat(parser.parse("[SCORE:7] Good. Next question?").codeMismatch()).isFalse();
    }

    @Test
    @DisplayName("[SKILL] is read and never spoken")
    void parsesSkill() {
        ParsedInterviewReply reply = parser.parse(
                "[SCORE:6] [TOPIC:Technical Skills] [SKILL:Spring Boot] [LEVEL:BASIC] What does @RestController do?");
        assertThat(reply.skill()).isEqualTo("Spring Boot");
        assertThat(reply.topic()).isEqualTo("Technical Skills");
        assertThat(reply.spokenText()).isEqualTo("What does @RestController do?");
    }

    @Test
    @DisplayName("[NOT_ENGLISH] is read and never spoken, in any of its spellings")
    void parsesNotEnglish() {
        ParsedInterviewReply reply = parser.parse("[SCORE:0] [NOT_ENGLISH] [REPHRASE] I can only assess answers in "
                + "English. Could you answer that again in English?");
        assertThat(reply.notEnglish()).isTrue();
        assertThat(reply.turnKind()).isEqualTo(TurnKind.REPHRASE);
        assertThat(reply.spokenText()).startsWith("I can only assess answers in English.");
        assertThat(parser.parse("[NOT IN ENGLISH] Please answer in English.").notEnglish()).isTrue();
        assertThat(parser.parse("[SCORE:7] Good. Next?").notEnglish()).isFalse();
    }
}
