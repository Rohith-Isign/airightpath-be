package com.rightpath.service.impl;

import java.time.LocalDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.entity.RoomVerificationSession;
import com.rightpath.enums.VerificationStatus;
import com.rightpath.repository.RoomVerificationRepository;
import com.rightpath.service.OpenAiVisionService;
import com.rightpath.service.RoomVerificationService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RoomVerificationServiceImpl implements RoomVerificationService {

    private final RoomVerificationRepository repo;
    private final OpenAiVisionService openAiVisionService;

    public RoomVerificationServiceImpl(
            RoomVerificationRepository repo,
            OpenAiVisionService openAiVisionService
    ) {
        this.repo = repo;
        this.openAiVisionService = openAiVisionService;
    }

    @Override
    @Transactional
    public String createSession() {

        String sessionId = UUID.randomUUID().toString();

        RoomVerificationSession session = RoomVerificationSession.builder()
                .sessionId(sessionId)
                .status(VerificationStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .build();

        repo.save(session);
        log.info("Room verification session {} created", sessionId);

        return sessionId;
    }

    @Override
    @Transactional
    public void verifyRoom(String sessionId, MultipartFile image) {

        RoomVerificationSession session =
                repo.findBySessionId(sessionId)
                        .orElseThrow(() -> new RuntimeException("Session not found"));

        try {

            byte[] bytes = image.getBytes();

            String aiResult = openAiVisionService.analyzeRoom(bytes);

            if (aiResult.contains("VERIFIED")) {

                session.setStatus(VerificationStatus.VERIFIED);
                session.setVerifiedAt(LocalDateTime.now());
                log.info("Room verification {} verified", sessionId);

            } else {

                session.setStatus(VerificationStatus.FAILED);
                session.setReason(aiResult);
                log.warn("Room verification {} failed", sessionId);
            }

            repo.save(session);

        } catch (Exception e) {
            log.error("Room verification {} errored", sessionId, e);

            session.setStatus(VerificationStatus.FAILED);
            session.setReason("Verification error");

            repo.save(session);
        }
    }

    @Override
    public RoomVerificationSession getStatus(String sessionId) {

        return repo.findBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found"));
    }
}