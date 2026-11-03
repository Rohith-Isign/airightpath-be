package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SkillMatcherTest {

    private static final Map<String, List<String>> SYNONYMS = Map.of(
            "spring boot", List.of("springboot", "spring boot", "spring-boot"),
            "javascript", List.of("javascript", "js"),
            "python", List.of("python", "py"));

    @Test
    void skillsAreSplitOnCommasSemicolonsAndNewlinesTrimmedAndDeduplicated() {
        assertEquals(List.of("java", "spring boot", "sql"),
                SkillMatcher.parseSkills(" Java ,Spring Boot;\nSQL, java,, "));
        assertEquals(List.of(), SkillMatcher.parseSkills(" , ;"));
        assertEquals(List.of(), SkillMatcher.parseSkills(null));
    }

    @Test
    void aPhraseBrokenAcrossALineOrHyphenatedStillMatches() {
        List<String> skills = List.of("spring boot", "java");
        assertEquals(100.0, SkillMatcher.matchPercent("Built services with Spring\nBoot and Java", skills, Map.of()));
        assertEquals(100.0, SkillMatcher.matchPercent("spring-boot, java", skills, Map.of()));
        assertEquals(100.0, SkillMatcher.matchPercent("SpringBoot JAVA", skills, Map.of()));
    }

    @Test
    void synonymsCountForTheirSkill() {
        assertEquals(100.0, SkillMatcher.matchPercent("Worked in springboot", List.of("spring boot"), SYNONYMS));
    }

    @Test
    void aSkillWithPunctuationMatches() {
        assertEquals(100.0, SkillMatcher.matchPercent("Node.js and C++ and C#", List.of("node.js", "c++", "c#"), Map.of()));
    }

    @Test
    void shortVariationsMustBeWholeWords() {
        // "js" lives inside "json" and "py" inside "happy"; neither is the skill.
        assertEquals(0.0, SkillMatcher.matchPercent("happy with json", List.of("javascript", "python"), SYNONYMS));
        assertEquals(100.0, SkillMatcher.matchPercent("Wrote JS and py scripts", List.of("javascript", "python"), SYNONYMS));
    }

    @Test
    void partialCoverageIsAFractionOfDistinctSkills() {
        assertEquals(50.0, SkillMatcher.matchPercent("java only", List.of("java", "sql"), Map.of()));
    }

    @Test
    void noTextOrNoSkillsScoresZeroWithoutDividingByZero() {
        assertEquals(0.0, SkillMatcher.matchPercent("", List.of("java"), Map.of()));
        assertEquals(0.0, SkillMatcher.matchPercent(null, List.of("java"), Map.of()));
        assertEquals(0.0, SkillMatcher.matchPercent("java", List.of(), Map.of()));
    }
}
