package com.rightpath.service.impl;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.rightpath.service.InterviewReportService;
import com.rightpath.service.StorageService;
import com.rightpath.util.InMemoryMultipartFile;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class InterviewReportServiceImpl implements InterviewReportService {

	private final StorageService storageService;

	@Value("${aws.s3.prefix.interview:interview}")
	private String containerName;

	public InterviewReportServiceImpl(StorageService storageService) {
		this.storageService = storageService;
	}

	@Override
	public String generateAndUpload(String sessionId, String transcript, String summary) {

		byte[] bytes = transcript.getBytes(StandardCharsets.UTF_8);

		MultipartFile file = new InMemoryMultipartFile("transcript", sessionId + ".txt", "text/plain", bytes);

		String url = storageService.uploadFile(containerName, sessionId + ".txt", file);
		log.info("Interview transcript uploaded for session {}", sessionId);
		return url;
	}

}
