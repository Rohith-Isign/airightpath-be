package com.rightpath.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.dto.MobileCaptureDto;
import com.rightpath.dto.ProctoringCaptureImage;
import com.rightpath.entity.CandidateInterviewSchedule;
import com.rightpath.entity.InterviewMobileCapture;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.CandidateInterviewScheduleRepository;
import com.rightpath.repository.InterviewMobileCaptureRepository;

/**
 * Stills taken from the candidate's paired phone: the room as it was approved,
 * and a frame every so often while the interview ran.
 *
 * <p>Uploaded from the interview page, signed in as the candidate, not from the
 * phone. The phone has only a pairing token that nothing on the server ties to
 * an interview, so a photo arriving from it could not be filed against the
 * right candidate — and its endpoints are open to anyone holding a token. The
 * desktop already receives the phone's picture and knows exactly which
 * interview it is sitting.</p>
 */
@Service
public class MobileCaptureService {

    private static final Logger log = LoggerFactory.getLogger(MobileCaptureService.class);

    private static final Set<String> KINDS = Set.of(InterviewMobileCapture.ROOM_PHOTO,
            InterviewMobileCapture.MONITOR_FRAME, InterviewMobileCapture.EXTRA_PERSON,
            InterviewMobileCapture.NO_CANDIDATE);

    /** How many photos of one alert kind an interview keeps: a room that stays wrong must not fill the store. */
    private static final int MAX_PER_ALERT_KIND = 40;

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    /**
     * More than an interview could plausibly need: one frame every twenty
     * seconds for the longest interview is about 180. A cap that is reached
     * means something is looping, and storing it all helps nobody.
     */
    private static final int MAX_PER_INTERVIEW = 500;

    private final InterviewMobileCaptureRepository captureRepository;
    private final CandidateInterviewScheduleRepository scheduleRepository;
    private final StorageService storageService;

    @Value("${aws.s3.prefix.proctoring:proctoring}")
    private String proctoringPrefix;

    @Value("${proctoring.capture.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes;


    public MobileCaptureService(InterviewMobileCaptureRepository captureRepository,
            CandidateInterviewScheduleRepository scheduleRepository, StorageService storageService) {
        this.captureRepository = captureRepository;
        this.scheduleRepository = scheduleRepository;
        this.storageService = storageService;
    }

    public MobileCaptureDto save(Long scheduleId, String actorEmail, String kind, String capturedAt,
            MultipartFile photo) {
        CandidateInterviewSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + scheduleId));
        if (actorEmail == null || actorEmail.isBlank() || schedule.getEmail() == null
                || !schedule.getEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Interview " + scheduleId + " is not assigned to the authenticated candidate");
        }
        return store(schedule, kind, capturedAt, photo);
    }

    /**
     * File a photo the phone sent, on the strength of its pairing token.
     *
     * <p>The caller has already resolved the token to this interview, which is
     * the authorisation: the phone has no login to check an owner against.</p>
     */
    public MobileCaptureDto saveForPairing(Long scheduleId, String kind, MultipartFile photo) {
        CandidateInterviewSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Interview schedule not found: " + scheduleId));
        return store(schedule, kind, null, photo);
    }

    private MobileCaptureDto store(CandidateInterviewSchedule schedule, String kind, String capturedAt,
            MultipartFile photo) {
        Long scheduleId = schedule.getId();
        String normalisedKind = kind == null ? "" : kind.trim().toUpperCase();
        if (!KINDS.contains(normalisedKind)) {
            throw new IllegalArgumentException("kind must be one of " + KINDS);
        }
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("photo is required");
        }
        String contentType = photo.getContentType() == null ? "" : photo.getContentType().toLowerCase();
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("photo must be a JPEG, PNG or WebP image");
        }
        if (photo.getSize() > maxFileSizeBytes) {
            throw new IllegalArgumentException("photo is larger than " + maxFileSizeBytes + " bytes");
        }

        if ((InterviewMobileCapture.EXTRA_PERSON.equals(normalisedKind) || InterviewMobileCapture.NO_CANDIDATE.equals(normalisedKind))
                && captureRepository.countByInterviewScheduleIdAndKind(scheduleId, normalisedKind) >= MAX_PER_ALERT_KIND) {
            throw new IllegalArgumentException("This interview already has the maximum number of " + normalisedKind + " photos");
        }

        long existing = captureRepository.countByInterviewScheduleId(scheduleId);
        if (existing >= MAX_PER_INTERVIEW) {
            throw new IllegalArgumentException("This interview already has the maximum number of phone captures");
        }

        int frameIndex = (int) existing;
        String extension = extensionFor(contentType);
        // Time and index in the name: a repeat capture never overwrites the one
        // before it, which is the point of keeping them as evidence.
        String fileName = "mobile-capture/interview/" + scheduleId + "/" + normalisedKind.toLowerCase() + "-"
                + System.currentTimeMillis() + "-" + frameIndex + extension;
        storageService.uploadFile(proctoringPrefix, fileName, photo);

        InterviewMobileCapture saved = captureRepository.save(InterviewMobileCapture.builder()
                .interviewScheduleId(scheduleId)
                .candidateEmail(schedule.getEmail())
                .kind(normalisedKind)
                .frameIndex(frameIndex)
                .containerName(proctoringPrefix)
                .fileName(fileName)
                .contentType(contentType)
                .sizeBytes(photo.getSize())
                .capturedAt(parseCapturedAt(capturedAt))
                .uploadedAt(LocalDateTime.now(ZoneOffset.UTC))
                .build());

        log.info("Stored {} phone capture #{} for interview {} ({} bytes)", normalisedKind, frameIndex, scheduleId,
                photo.getSize());
        return MobileCaptureDto.from(saved);
    }

    public List<MobileCaptureDto> list(Long scheduleId) {
        return captureRepository.findByInterviewScheduleIdOrderByCapturedAtAscIdAsc(scheduleId).stream()
                .map(MobileCaptureDto::from)
                .toList();
    }

    public ProctoringCaptureImage loadImage(Long captureId) {
        InterviewMobileCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new ResourceNotFoundException("Phone capture not found: " + captureId));
        byte[] bytes = storageService.downloadFile(capture.getContainerName(), capture.getFileName());
        String contentType = capture.getContentType() != null ? capture.getContentType() : "image/jpeg";
        String downloadName = "phone-" + capture.getKind().toLowerCase() + "-" + capture.getInterviewScheduleId() + "-"
                + capture.getFrameIndex() + extensionFor(contentType);
        return new ProctoringCaptureImage(bytes, contentType, downloadName);
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    /** The browser's clock if it parses, otherwise now. A bad stamp must not cost the photo. */
    private LocalDateTime parseCapturedAt(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(raw.trim()), ZoneOffset.UTC);
            } catch (Exception ignored) {
                // fall through to now
            }
        }
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
