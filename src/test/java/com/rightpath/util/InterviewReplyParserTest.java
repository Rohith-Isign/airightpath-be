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
}
