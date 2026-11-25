package com.rightpath.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.rightpath.service.TranscriptionVocabulary;

class TechVocabularyTest {

    @Test
    @DisplayName("common technical mishearings are put right")
    void correctsMishearings() {
        assertThat(TechVocabulary.correct("I used java script and type script with node js"))
                .isEqualTo("I used JavaScript and TypeScript with Node.js");
        assertThat(TechVocabulary.correct("we stored it in my sequel and later post gress"))
                .isEqualTo("we stored it in MySQL and later PostgreSQL");
        assertThat(TechVocabulary.correct("I built it with spring boat and J D B C"))
                .isEqualTo("I built it with Spring Boot and JDBC");
        assertThat(TechVocabulary.correct("a hash map uses hash code and the jason response"))
                .isEqualTo("a HashMap uses hashCode and the JSON response");
        assertThat(TechVocabulary.correct("deployed on cuber netes using docker and micro services"))
                .isEqualTo("deployed on Kubernetes using Docker and microservices");
        assertThat(TechVocabulary.correct("in java I wrote a rest api and used num pie"))
                .isEqualTo("in Java I wrote a REST API and used NumPy");
        assertThat(TechVocabulary.correct("the A P I returns html and css"))
                .isEqualTo("the API returns HTML and CSS");
    }

    @Test
    @DisplayName("ordinary English is left alone")
    void leavesEnglishAlone() {
        String english = "How would the team react if we take a rest and spring into action, Jason said, so I "
                + "am going to go fast and keep it simple and quick.";
        assertThat(TechVocabulary.correct(english)).isEqualTo(english);
    }

    @Test
    @DisplayName("the terms a job description names are picked out for the hint")
    void termsInText() {
        assertThat(TechVocabulary.termsIn("Strong Core Java, Spring Boot, React, PostgreSQL and Git experience"))
                .contains("Core Java", "Spring Boot", "React", "PostgreSQL", "Git");
    }

    @Test
    @DisplayName("the hint said back on a quiet chunk is recognised")
    void hintEcho() {
        String hint = "The speaker's name is Sai. A technical interview answer mentioning Java, Spring Boot, React.";
        assertThat(TranscriptionVocabulary.echoesHint("Java, Spring Boot, React.", hint)).isTrue();
        assertThat(TranscriptionVocabulary.echoesHint("I used Spring Boot to build the REST layer", hint)).isFalse();
    }
}
