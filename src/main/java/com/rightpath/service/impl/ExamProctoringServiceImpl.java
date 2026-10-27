package com.rightpath.service.impl;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.ProctoringCaptureDto;
import com.rightpath.dto.ProctoringCaptureImage;
import com.rightpath.entity.Assessment;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.ExamProctoringCapture;
import com.rightpath.enums.ProctoringCaptureType;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.AssessmentRepository;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.ExamProctoringCaptureRepository;
import com.rightpath.service.ExamProctoringService;
import com.rightpath.service.StorageService;

import jakarta.transaction.Transactional;

/**
 * Persists pre-exam captures to object storage and keeps a pointer row per image.
 *
 * <p>A failure here does block the candidate. Where the client has a capture
 * configured as required, it holds the exam start until the upload succeeds,
 * offering a retry that re-sends the frames already taken — a photo that never
 * reached storage is no evidence of who sat the exam. Errors returned from this
 * service reach someone waiting to start an exam, so they are worth making
 * accurate.</p>
 *
 * <p>What it guarantees is that anything it does store is attributable: the
 * caller must own the assessment attempt they are uploading against.</p>
 */
@Transactional
@Service
public class ExamProctoringServiceImpl implements ExamProctoringService {

    private static final Logger logger = LoggerFactory.getLogger(ExamProctoringServiceImpl.class);

    /** An identity photo is a single frame, so it always occupies slot 0. */
    private static final int IDENTITY_PHOTO_FRAME_INDEX = 0;

    private static final String DEFAULT_CONTENT_TYPE = "image/jpeg";

    @Value("${aws.s3.prefix.proctoring:proctoring}")
    private String proctoringPrefix;

    /** Guards against a client streaming something far larger than a webcam frame. */
    @Value("${proctoring.capture.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes;

    /** The sweep length is client-configurable, so accept a range rather than a fixed count. */
    @Value("${proctoring.room-scan.max-frames:32}")
    private int maxRoomScanFrames;

    private final ExamProctoringCaptureRepository captureRepository;
    private final AssessmentRepository assessmentRepository;
    private final CandidateInterviewScheduleRepository scheduleRepository;
    private final StorageService storageService;

    public ExamProctoringServiceImpl(ExamProctoringCaptureRepository captureRepository,
            AssessmentRepository assessmentRepository,
            CandidateInterviewScheduleRepository scheduleRepository, StorageService storageService) {
        this.captureRepository = captureRepository;
        this.assessmentRepository = assessmentRepository;
        this.scheduleRepository = scheduleRepository;
        this.storageService = storageService;
    }

    @Override
    public ProctoringCaptureDto saveIdentityPhoto(String assessmentId, String candidateEmail, String capturedAt,
            MultipartFile photo, String actorEmail) {

        Assessment assessment = requireOwnedAssessment(assessmentId, candidateEmail, actorEmail);
        validateImage(photo, "photo");

        ExamProctoringCapture capture = store(CaptureOwner.ofAssessment(assessment),
                ProctoringCaptureType.IDENTITY_PHOTO, IDENTITY_PHOTO_FRAME_INDEX, photo,
                parseCapturedAt(capturedAt));

        logger.info("Stored identity photo for assessment {} (candidate {})", assessment.getId(),
                assessment.getCandidateEmail());
        return ProctoringCaptureDto.from(capture);
    }

    @Override
    public List<ProctoringCaptureDto> saveRoomScan(String assessmentId, String candidateEmail, String capturedAt,
            List<MultipartFile> frames, String actorEmail) {

        Assessment assessment = requireOwnedAssessment(assessmentId, candidateEmail, actorEmail);

        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("At least one room scan frame is required");
        }
        if (frames.size() > maxRoomScanFrames) {
            throw new IllegalArgumentException(
                    "A room scan may contain at most " + maxRoomScanFrames + " frames, received " + frames.size());
        }
        for (int i = 0; i < frames.size(); i++) {
            validateImage(frames.get(i), "frames[" + i + "]");
        }

        // A repeat scan can be shorter than the last one, so clear the old sweep
        // rather than overwriting frame by frame and leaving a stale tail behind.
        captureRepository.deleteByAssessmentIdAndCaptureType(assessment.getId(),
                ProctoringCaptureType.ROOM_SCAN_FRAME);
        captureRepository.flush();

        LocalDateTime captureTime = parseCapturedAt(capturedAt);
        List<ProctoringCaptureDto> stored = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            stored.add(ProctoringCaptureDto.from(store(CaptureOwner.ofAssessment(assessment),
                    ProctoringCaptureType.ROOM_SCAN_FRAME, index, frames.get(index), captureTime)));
        }

        logger.info("Stored {} room scan frames for assessment {} (candidate {})", stored.size(), assessment.getId(),
                assessment.getCandidateEmail());
        return stored;
    }

    @Override
    public ProctoringCaptureDto saveInterviewIdentityPhoto(String scheduleId, String candidateEmail,
            String capturedAt, MultipartFile photo, String actorEmail) {

        CandidateInterviewSchedule schedule = requireOwnedSchedule(scheduleId, candidateEmail, actorEmail);
        validateImage(photo, "photo");

        ExamProctoringCapture capture = store(CaptureOwner.ofInterview(schedule),
                ProctoringCaptureType.IDENTITY_PHOTO, IDENTITY_PHOTO_FRAME_INDEX, photo,
                parseCapturedAt(capturedAt));

        logger.info("Stored identity photo for interview {} (candidate {})", schedule.getId(), schedule.getEmail());
        return ProctoringCaptureDto.from(capture);
    }

    @Override
    public List<ProctoringCaptureDto> saveInterviewRoomScan(String scheduleId, String candidateEmail,
            String capturedAt, List<MultipartFile> frames, String actorEmail) {

        CandidateInterviewSchedule schedule = requireOwnedSchedule(scheduleId, candidateEmail, actorEmail);

        if (frames == null || frames.isEmpty()) {
            throw new IllegalArgumentException("At least one room scan frame is required");
        }
        if (frames.size() > maxRoomScanFrames) {
            throw new IllegalArgumentException(
                    "A room scan may contain at most " + maxRoomScanFrames + " frames, received " + frames.size());
        }
        for (int i = 0; i < frames.size(); i++) {
            validateImage(frames.get(i), "frames[" + i + "]");
        }

        // A repeat scan can be shorter than the last, so clear the old sweep
        // rather than overwriting frame by frame and leaving a stale tail.
        captureRepository.deleteByInterviewScheduleIdAndCaptureType(schedule.getId(),
                ProctoringCaptureType.ROOM_SCAN_FRAME);
        captureRepository.flush();

        LocalDateTime captureTime = parseCapturedAt(capturedAt);
        List<ProctoringCaptureDto> stored = new ArrayList<>(frames.size());
        for (int index = 0; index < frames.size(); index++) {
            stored.add(ProctoringCaptureDto.from(store(CaptureOwner.ofInterview(schedule),
                    ProctoringCaptureType.ROOM_SCAN_FRAME, index, frames.get(index), captureTime)));
        }

        logger.info("Stored {} room scan frames for interview {} (candidate {})", stored.size(), schedule.getId(),
                schedule.getEmail());
        return stored;
    }

    @Override
    public List<ProctoringCaptureDto> getInterviewCaptures(Long scheduleId) {
        return captureRepository.findByInterviewScheduleIdOrderByCaptureTypeAscFrameIndexAsc(scheduleId).stream()
                .map(ProctoringCaptureDto::from)
                .toList();
    }

    /**
     * Loads the interview and proves it belongs to the candidate uploading.
     *
     * <p>Mirrors {@code requireOwnedAssessment}: without the third check a
     * candidate could post their own photo against somebody else's interview.</p>
     */
    private CandidateInterviewSchedule requireOwnedSchedule(String rawScheduleId, String candidateEmail,
            String actorEmail) {
        if (rawScheduleId == null || rawScheduleId.isBlank()) {
            throw new IllegalArgumentException("scheduleId is required");
        }
        long id;
        try {
            id = Long.parseLong(rawScheduleId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("scheduleId must be a number, received: " + rawScheduleId);
        }

        CandidateInterviewSchedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + id));

        if (actorEmail == null || actorEmail.isBlank()) {
            throw new AccessDeniedException("An authenticated candidate is required to upload a proctoring capture");
        }
        if (candidateEmail == null || !candidateEmail.trim().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("candidateEmail must match the authenticated candidate");
        }
        if (schedule.getEmail() == null || !schedule.getEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Interview " + id + " is not assigned to the authenticated candidate");
        }
        return schedule;
    }

    @Override
    public List<ProctoringCaptureDto> getCaptures(Long assessmentId) {
        return captureRepository.findByAssessmentIdOrderByCaptureTypeAscFrameIndexAsc(assessmentId).stream()
                .map(ProctoringCaptureDto::from)
                .toList();
    }

    @Override
    public List<ProctoringCaptureDto> getCaptures(String candidateEmail, String jobPrefix) {
        return captureRepository
                .findByCandidateEmailAndJobPrefixOrderByAssessmentIdAscCaptureTypeAscFrameIndexAsc(candidateEmail,
                        jobPrefix)
                .stream()
                .map(ProctoringCaptureDto::from)
                .toList();
    }

    @Override
    public ProctoringCaptureImage loadImage(Long captureId) {
        ExamProctoringCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new ResourceNotFoundException("Proctoring capture not found: " + captureId));

        byte[] bytes = storageService.downloadFile(capture.getContainerName(), capture.getFileName());
        String contentType = capture.getContentType() != null ? capture.getContentType() : DEFAULT_CONTENT_TYPE;
        String downloadName = capture.getCaptureType().name().toLowerCase(Locale.ROOT) + "-" + capture.getAssessmentId()
                + "-" + capture.getFrameIndex() + extensionFor(contentType);

        return new ProctoringCaptureImage(bytes, contentType, downloadName);
    }

    /**
     * Uploads one frame and records (or replaces) the row pointing at it.
     *
     * <p>The storage key is derived from the slot, so a re-capture overwrites the
     * object it replaces instead of leaving an orphan behind in the bucket.</p>
     */
    /**
     * What a capture belongs to.
     *
     * <p>Exactly one of the two ids is set. They come from separate sequences,
     * so an interview capture must never be filed under an assessment id — a
     * reviewer would be shown the wrong candidate's photo.</p>
     */
    private record CaptureOwner(Long assessmentId, Long interviewScheduleId, String candidateEmail,
            String jobPrefix) {

        static CaptureOwner ofAssessment(Assessment assessment) {
            return new CaptureOwner(assessment.getId(), null, assessment.getCandidateEmail(),
                    assessment.getJobPrefix());
        }

        static CaptureOwner ofInterview(CandidateInterviewSchedule schedule) {
            return new CaptureOwner(null, schedule.getId(), schedule.getEmail(), schedule.getJobPrefix());
        }

        /**
         * The id segment of a storage key.
         *
         * <p>Assessments keep the bare id they have always used: changing the
         * shape would orphan every exam capture already in the bucket, since
         * nothing rewrites old keys. Interviews get their own sub-path, which is
         * what keeps assessment 42 and interview-schedule 42 apart.</p>
         */
        String storagePath() {
            return assessmentId != null ? String.valueOf(assessmentId) : "interview/" + interviewScheduleId;
        }
    }

    private ExamProctoringCapture store(CaptureOwner owner, ProctoringCaptureType type, int frameIndex,
            MultipartFile file, LocalDateTime capturedAt) {

        String contentType = file.getContentType() != null ? file.getContentType() : DEFAULT_CONTENT_TYPE;
        String fileName = storageKey(owner, type, frameIndex, contentType);
        storageService.uploadFile(proctoringPrefix, fileName, file);

        ExamProctoringCapture capture = (owner.assessmentId() != null
                ? captureRepository.findByAssessmentIdAndCaptureTypeAndFrameIndex(
                        owner.assessmentId(), type, frameIndex)
                : captureRepository.findByInterviewScheduleIdAndCaptureTypeAndFrameIndex(
                        owner.interviewScheduleId(), type, frameIndex))
                .orElseGet(ExamProctoringCapture::new);

        capture.setAssessmentId(owner.assessmentId());
        capture.setInterviewScheduleId(owner.interviewScheduleId());
        capture.setCandidateEmail(owner.candidateEmail());
        capture.setJobPrefix(owner.jobPrefix());
        capture.setCaptureType(type);
        capture.setFrameIndex(frameIndex);
        capture.setContainerName(proctoringPrefix);
        capture.setFileName(fileName);
        capture.setContentType(contentType);
        capture.setSizeBytes(file.getSize());
        capture.setCapturedAt(capturedAt);
        // UTC for the same reason as capturedAt — both columns are zone-less.
        capture.setUploadedAt(LocalDateTime.now(ZoneOffset.UTC));

        return captureRepository.save(capture);
    }

    private String storageKey(CaptureOwner owner, ProctoringCaptureType type, int frameIndex, String contentType) {
        String extension = extensionFor(contentType);
        // The owner path keeps exam and interview keys in separate folders.
        // Without it, assessment 42 and interview-schedule 42 would write to the
        // same object and each would overwrite the other's evidence.
        if (type == ProctoringCaptureType.IDENTITY_PHOTO) {
            return "identity-photo/" + owner.storagePath() + extension;
        }
        return "room-scan/" + owner.storagePath() + "/frame-" + frameIndex + extension;
    }

    private String extensionFor(String contentType) {
        if (contentType == null) {
            return ".jpg";
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    /**
     * Resolves the attempt and refuses anything the caller does not own.
     *
     * <p>Both the assessment and the {@code candidateEmail} in the request have to
     * line up with the authenticated caller — a capture filed against someone
     * else's attempt would be worse than no capture at all.</p>
     */
    private Assessment requireOwnedAssessment(String rawAssessmentId, String candidateEmail, String actorEmail) {
        Long id = parseAssessmentId(rawAssessmentId);

        Assessment assessment = assessmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Assessment not found: " + id));

        if (actorEmail == null || actorEmail.isBlank()) {
            throw new AccessDeniedException("An authenticated candidate is required to upload a proctoring capture");
        }
        if (candidateEmail == null || !candidateEmail.trim().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("candidateEmail must match the authenticated candidate");
        }
        if (assessment.getCandidateEmail() == null
                || !assessment.getCandidateEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Assessment " + id + " is not assigned to the authenticated candidate");
        }
        return assessment;
    }

    private Long parseAssessmentId(String rawAssessmentId) {
        if (rawAssessmentId == null || rawAssessmentId.isBlank()) {
            throw new IllegalArgumentException("assessmentId is required");
        }
        try {
            return Long.parseLong(rawAssessmentId.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("assessmentId must be numeric, received: " + rawAssessmentId);
        }
    }

    private void validateImage(MultipartFile file, String field) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException(
                    field + " exceeds the " + maxFileSizeBytes + " byte limit for a proctoring capture");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new IllegalArgumentException(field + " must be an image, received content type: " + contentType);
        }
    }

    /**
     * Reads the client's capture timestamp, falling back to server time.
     *
     * <p>{@code capturedAt} comes off the browser clock and is informational, so a
     * malformed value is logged and replaced rather than failing an upload that
     * carries a perfectly good photo.</p>
     */
    private LocalDateTime parseCapturedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        String value = raw.trim();
        try {
            // UTC, not ZoneId.systemDefault(). The column is zone-less, so what
            // it means has to be fixed by convention rather than by wherever the
            // server happens to run — otherwise the same instant reads
            // differently after a deploy to another region, and every stored row
            // becomes ambiguous. The DTO republishes these as instants so the
            // browser is never left guessing either.
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Not an offset timestamp; try a local one below.
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            logger.warn("Unparseable capturedAt '{}' on a proctoring capture; recording server time instead", value);
            return LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
