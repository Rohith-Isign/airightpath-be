package com.rightpath.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.rightpath.entity.ExamProctoringCapture;
import com.rightpath.enums.ProctoringCaptureType;

/**
 * Proctoring captures filed against an interview rather than an assessment.
 *
 * <p>The two ids come from independent sequences, so the property that matters
 * is separation: assessment 42 and interview-schedule 42 must not be able to see
 * or overwrite each other's evidence. Exercised against the real MySQL schema,
 * which also proves {@code ddl-auto} added the column and relaxed
 * {@code assessment_id} to nullable — a capture row could not be written at all
 * otherwise.</p>
 *
 * <p>Fixtures use ids far outside real ranges and are rolled back.</p>
 */
@DataJpaTest
@ActiveProfiles("dev")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InterviewCaptureStorageTest {

    /** Deliberately identical, to prove the two id spaces stay separate. */
    private static final long SHARED_ID = 987_654_321L;

    private static final String CANDIDATE = "capture.fixture@example.test";

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private ExamProctoringCaptureRepository captureRepository;

    private ExamProctoringCapture capture(Long assessmentId, Long scheduleId, ProctoringCaptureType type,
            int frameIndex) {
        ExamProctoringCapture capture = new ExamProctoringCapture();
        capture.setAssessmentId(assessmentId);
        capture.setInterviewScheduleId(scheduleId);
        capture.setCandidateEmail(CANDIDATE);
        capture.setJobPrefix("CAPTURE-FIXTURE");
        capture.setCaptureType(type);
        capture.setFrameIndex(frameIndex);
        capture.setContainerName("fixture");
        capture.setFileName("fixture/" + type + "-" + frameIndex + ".jpg");
        capture.setContentType("image/jpeg");
        capture.setSizeBytes(1L);
        capture.setCapturedAt(LocalDateTime.now());
        capture.setUploadedAt(LocalDateTime.now());
        return capture;
    }

    @Test
    void anInterviewCaptureStoresWithNoAssessmentId() {
        // Proves assessment_id really is nullable now. Before the change this
        // insert failed outright, so interviews had nowhere to put evidence.
        ExamProctoringCapture saved = entityManager.persistFlushFind(
                capture(null, SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0));

        assertNull(saved.getAssessmentId());
        assertEquals(SHARED_ID, saved.getInterviewScheduleId());
    }

    @Test
    void interviewAndAssessmentCapturesWithTheSameIdDoNotMix() {
        // The whole reason for a second column. Reusing assessment_id would make
        // these two rows indistinguishable, and a reviewer could be shown the
        // wrong candidate's photo.
        entityManager.persist(capture(SHARED_ID, null, ProctoringCaptureType.IDENTITY_PHOTO, 0));
        entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0));
        entityManager.flush();
        entityManager.clear();

        List<ExamProctoringCapture> byAssessment =
                captureRepository.findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(SHARED_ID);
        List<ExamProctoringCapture> byInterview =
                captureRepository.findByInterviewScheduleIdOrderByCaptureTypeAscFrameIndexAsc(SHARED_ID);

        assertEquals(1, byAssessment.size(), "assessment lookup picked up the interview capture");
        assertEquals(1, byInterview.size(), "interview lookup picked up the assessment capture");
        assertNull(byAssessment.get(0).getInterviewScheduleId());
        assertNull(byInterview.get(0).getAssessmentId());
    }

    @Test
    void clearingAnInterviewSweepLeavesTheAssessmentSweepAlone() {
        entityManager.persist(capture(SHARED_ID, null, ProctoringCaptureType.ROOM_SCAN_FRAME, 0));
        entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.ROOM_SCAN_FRAME, 0));
        entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.ROOM_SCAN_FRAME, 1));
        entityManager.flush();

        captureRepository.deleteByInterviewScheduleIdAndCaptureType(SHARED_ID,
                ProctoringCaptureType.ROOM_SCAN_FRAME);
        captureRepository.flush();
        entityManager.clear();

        assertTrue(captureRepository
                .findByInterviewScheduleIdOrderByCaptureTypeAscFrameIndexAsc(SHARED_ID).isEmpty(),
                "the interview sweep should have been cleared");
        assertEquals(1,
                captureRepository.findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(SHARED_ID).size(),
                "clearing an interview sweep must not touch the assessment's");
    }

    @Test
    void theDatabaseRefusesTwoCapturesOnTheSameInterviewSlot() {
        // Proves uk_proctoring_capture_interview_slot is really in the schema.
        // The service updates the existing row rather than inserting a second,
        // so nothing above this would notice the constraint missing — and
        // without it the original (assessment_id, ...) key constrains nothing
        // here, because MySQL treats each NULL assessment_id as distinct.
        entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0));
        entityManager.flush();

        // The id is generated by the column, so Hibernate inserts on persist
        // rather than waiting for the flush — both are inside the assertion so
        // the test does not depend on which one trips.
        assertThrows(ConstraintViolationException.class, () -> {
            entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0));
            entityManager.flush();
        }, "a second capture on the same interview slot should violate the unique key");
    }

    @Test
    void aReCapturedInterviewPhotoReplacesRatherThanAccumulates() {
        // What the interview-specific unique constraint buys. MySQL treats NULLs
        // as distinct, so the original (assessment_id, type, frame) constraint
        // does not constrain interview rows at all.
        entityManager.persist(capture(null, SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0));
        entityManager.flush();
        entityManager.clear();

        ExamProctoringCapture existing = captureRepository
                .findByInterviewScheduleIdAndCaptureTypeAndFrameIndex(
                        SHARED_ID, ProctoringCaptureType.IDENTITY_PHOTO, 0)
                .orElseThrow();
        existing.setFileName("fixture/replaced.jpg");
        entityManager.persist(existing);
        entityManager.flush();
        entityManager.clear();

        List<ExamProctoringCapture> photos =
                captureRepository.findByInterviewScheduleIdOrderByCaptureTypeAscFrameIndexAsc(SHARED_ID);
        assertEquals(1, photos.size(), "a re-capture should replace the photo, not add a second");
        assertEquals("fixture/replaced.jpg", photos.get(0).getFileName());
    }
}
