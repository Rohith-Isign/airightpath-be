package com.rightpath.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.rightpath.util.AtsScorer.Posting;
import com.rightpath.util.AtsScorer.Result;

class AtsScorerTest {

    private static final String DESCRIPTION = "We are hiring an associate software engineer to build responsive web "
            + "applications. You will develop user interfaces with React, write REST APIs in Node.js, design SQL "
            + "database queries, and review code with the team through Git pull requests. You will debug production "
            + "issues and write unit tests.";

    private static final Posting JOB = new Posting("HTML, CSS, JavaScript, React, Node.js, SQL, Git, Problem Solving",
            DESCRIPTION, "0-2 Years (Freshers can apply)", "B.Tech", "Associate Software Engineer",
            "Full Stack Developer");

    private static final String STRONG = "Priya Sharma. Fresher. B.Tech in Computer Science 2025. Full Stack Developer "
            + "intern. Built responsive web applications with React and JavaScript, HTML and CSS. Wrote REST APIs in "
            + "Node.js and designed SQL database queries. Used Git pull requests for code review, wrote unit tests "
            + "and debugged production issues. Strong problem solving. Associate software engineer projects.";

    private static final String SKILLS_ONLY = "Ravi Kumar. Skills: HTML, CSS, JavaScript, React, Node.js, SQL, Git, "
            + "Problem Solving.";

    private static final String UNRELATED = "Anita Rao. Head chef with eight years of experience in restaurant "
            + "kitchens. Menu planning, food costing, inventory and staff scheduling. Diploma in Hotel Management.";

    @Test
    void aResumeThatFitsTheWholePostingScoresHigh() {
        Result result = AtsScorer.score(STRONG, JOB, Map.of());
        assertTrue(result.percent() >= 80, "was " + result.percent() + " from " + result.components());
    }

    @Test
    void naming_the_skills_alone_scores_below_a_resume_that_also_shows_the_work() {
        double skillsOnly = AtsScorer.score(SKILLS_ONLY, JOB, Map.of()).percent();
        double full = AtsScorer.score(STRONG, JOB, Map.of()).percent();
        assertTrue(full > skillsOnly + 10, "full " + full + " vs skills-only " + skillsOnly);
    }

    @Test
    void anUnrelatedResumeScoresLow() {
        Result result = AtsScorer.score(UNRELATED, JOB, Map.of());
        assertTrue(result.percent() < 25, "was " + result.percent() + " from " + result.components());
    }

    @Test
    void aPostingWithNoSkillsListIsScoredOnTheRest() {
        Posting noSkills = new Posting("", DESCRIPTION, "0-2 Years", "B.Tech", "Associate Software Engineer", "Full Stack Developer");
        double strong = AtsScorer.score(STRONG, noSkills, Map.of()).percent();
        double unrelated = AtsScorer.score(UNRELATED, noSkills, Map.of()).percent();
        assertTrue(strong > 60, "strong was " + strong);
        assertTrue(unrelated < strong - 30, "unrelated " + unrelated + " vs strong " + strong);
    }

    @Test
    void experienceIsMeasuredAgainstTheYearsAskedFor() {
        Posting senior = new Posting("Java", "", "5+ years", "", "", "");
        double twoOfFive = AtsScorer.score("Java developer with 2 years of experience in Java.", senior, Map.of())
                .percent();
        double fiveOfFive = AtsScorer.score("Java developer with 5 years of experience in Java.", senior, Map.of())
                .percent();
        assertTrue(fiveOfFive > twoOfFive, "5y " + fiveOfFive + " vs 2y " + twoOfFive);
        // 2 of 5 years is 40% on experience; skills are full, so the blend sits between.
        assertTrue(twoOfFive < 100 && twoOfFive > 40, "was " + twoOfFive);
    }

    @Test
    void aLowerDegreeThanAskedForLosesTheEducationPart() {
        Posting masters = new Posting("Java", "", "", "M.Tech", "", "");
        double bachelors = AtsScorer.score("Java developer. B.Tech graduate.", masters, Map.of()).percent();
        double holder = AtsScorer.score("Java developer. M.Tech graduate.", masters, Map.of()).percent();
        assertTrue(holder > bachelors, "m.tech " + holder + " vs b.tech " + bachelors);
    }

    @Test
    void theYearsAskedForAreReadFromTheCommonWordings() {
        assertEquals(0, AtsScorer.requiredYears("0-2 Years (Freshers can apply)", null));
        assertEquals(3, AtsScorer.requiredYears("3+ years", null));
        assertEquals(0, AtsScorer.requiredYears("Fresher", null));
        assertEquals(1, AtsScorer.requiredYears("1", null));
        assertEquals(4, AtsScorer.requiredYears("", "Minimum 4 years of Java experience"));
        assertNull(AtsScorer.requiredYears("", "No numbers here"));
    }

    @Test
    void educationLevelsAreRecognised() {
        assertEquals(1, AtsScorer.educationLevel("B.Tech"));
        assertEquals(1, AtsScorer.educationLevel("B.E in Computer Science"));
        assertEquals(2, AtsScorer.educationLevel("M.Tech / MCA"));
        assertEquals(3, AtsScorer.educationLevel("PhD"));
        assertEquals(0, AtsScorer.educationLevel("anything goes"));
        // "be" is a common word and must not read as a degree.
        assertEquals(0, AtsScorer.educationLevel("to be or not to be"));
    }

    @Test
    void anEmptyResumeScoresZero() {
        assertEquals(0.0, AtsScorer.score("", JOB, Map.of()).percent());
    }
}
