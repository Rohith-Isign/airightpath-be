package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.entity.QuestionBankAssignment;
import com.rightpath.entity.QuestionBankItem;
import com.rightpath.enums.AssessmentType;
import com.rightpath.repository.QuestionBankAssignmentRepository;
import com.rightpath.repository.QuestionBankItemRepository;

/**
 * What counts as "the same question", and that giving a candidate a paper records each question once.
 *
 * <p>Everything the bank shows — which questions were never used, how often one was given, to whom —
 * rests on two questions being recognised as one when they should be. Ids are renumbered every time a
 * paper is built, and a generator may add a field, so neither can be part of the identity.</p>
 */
class QuestionBankTrackerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private QuestionBankItemRepository items;
    private QuestionBankAssignmentRepository assignments;
    private QuestionBankTracker tracker;
    private final Map<String, QuestionBankItem> stored = new HashMap<>();
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        items = mock(QuestionBankItemRepository.class);
        assignments = mock(QuestionBankAssignmentRepository.class);
        tracker = new QuestionBankTracker(items, assignments);

        // A tiny in-memory item table, keyed the way the real unique constraint is.
        when(items.findByJobPrefixAndAssessmentTypeAndContentHash(any(), any(), any())).thenAnswer(call -> {
            String key = call.getArgument(0) + "|" + call.getArgument(1) + "|" + call.getArgument(2);
            return Optional.ofNullable(stored.get(key));
        });
        when(items.save(any(QuestionBankItem.class))).thenAnswer(call -> {
            QuestionBankItem item = call.getArgument(0);
            item.setId(ids.incrementAndGet());
            stored.put(item.getJobPrefix() + "|" + item.getAssessmentType() + "|" + item.getContentHash(), item);
            return item;
        });
    }

    private JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void theSameQuestionWithADifferentIdIsTheSameQuestion() throws Exception {
        String one = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"id\":1,\"question\":\"What is 2+2?\",\"options\":{\"A\":\"3\",\"B\":\"4\"}}"));
        String renumbered = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"id\":9,\"questionText\":\"  what IS 2+2? \",\"options\":{\"B\":\"4\",\"A\":\"3\"},\"marks\":2}"));
        assertEquals(one, renumbered);
    }

    @Test
    void differentOptionsMakeADifferentQuestion() throws Exception {
        String one = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"question\":\"Pick one\",\"options\":{\"A\":\"x\",\"B\":\"y\"}}"));
        String other = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"question\":\"Pick one\",\"options\":{\"A\":\"x\",\"B\":\"z\"}}"));
        assertNotEquals(one, other);
    }

    @Test
    void tabsAndLineBreaksInTheWordingAreNotADifference() throws Exception {
        // \t and \n, not just spaces: a question pasted from a document carries them.
        String plain = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"question\":\"Which is larger?\",\"options\":{\"A\":\"two apples\",\"B\":\"one\"}}"));
        String messy = QuestionBankTracker.fingerprint(AssessmentType.APTITUDE,
                node("{\"question\":\"Which\\tis\\n larger?\\r\\n\",\"options\":{\"A\":\"two\\n apples\",\"B\":\" one \"}}"));
        assertEquals(plain, messy);
    }

    @Test
    void aCodingQuestionIsItsTitleAndProblemStatement() throws Exception {
        String one = QuestionBankTracker.fingerprint(AssessmentType.CODING,
                node("{\"id\":\"1\",\"title\":\"Reverse\",\"description\":\"Reverse a string.\",\"marks\":5}"));
        String reordered = QuestionBankTracker.fingerprint(AssessmentType.CODING,
                node("{\"id\":\"7\",\"description\":\"Reverse a string.\",\"title\":\"Reverse\",\"Difficulty\":\"Easy\"}"));
        assertEquals(one, reordered);
    }

    @Test
    void generatedQuestionsAreKeptOnceEvenWhenGeneratedAgain() {
        String paper = "[{\"question\":\"Q1\",\"options\":{\"A\":\"a\"}},{\"question\":\"Q2\",\"options\":{\"A\":\"b\"}}]";
        assertEquals(2, tracker.recordGenerated("JOB-1", AssessmentType.APTITUDE, paper, "admin"));
        assertEquals(0, tracker.recordGenerated("JOB-1", AssessmentType.APTITUDE, paper, "admin"),
                "generating the same questions again adds nothing");
    }

    @Test
    void givingACandidateAPaperRecordsEachQuestionOnce() {
        String paper = "{\"questions\":[{\"question\":\"Q1\",\"options\":[\"a\",\"b\"]},{\"question\":\"Q2\",\"options\":[\"c\"]}]}";
        when(assignments.existsByItemIdAndAssessmentId(anyLong(), anyLong())).thenReturn(false);

        int added = tracker.recordAssignment("JOB-1", AssessmentType.APTITUDE, paper, 55L, "c@example.com",
                LocalDateTime.now(), "admin");

        assertEquals(2, added);
        verify(assignments, org.mockito.Mockito.times(2)).save(any(QuestionBankAssignment.class));
    }

    @Test
    void recordingTheSameAssessmentTwiceDoesNotDoubleCount() {
        String paper = "[{\"question\":\"Q1\",\"options\":{\"A\":\"a\"}}]";
        when(assignments.existsByItemIdAndAssessmentId(anyLong(), anyLong())).thenReturn(true);

        int added = tracker.recordAssignment("JOB-1", AssessmentType.APTITUDE, paper, 55L, "c@example.com",
                LocalDateTime.now(), "admin");

        assertEquals(0, added);
        verify(assignments, never()).save(any(QuestionBankAssignment.class));
    }

    @Test
    void aPaperThatIsNotQuestionsRecordsNothingAndDoesNotThrow() {
        assertEquals(0, tracker.recordAssignment("JOB-1", AssessmentType.APTITUDE, "this is not json", 1L, "c@example.com",
                LocalDateTime.now(), "admin"));
        assertEquals(0, tracker.recordAssignment("JOB-1", AssessmentType.APTITUDE, null, 1L, "c@example.com",
                LocalDateTime.now(), "admin"));
    }
}
