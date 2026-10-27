package com.rightpath.service.impl;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.rightpath.service.SpeechToTextService;
import com.rightpath.util.TranscriptionSanitizer;

import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

@Service
public class SpeechToTextServiceImpl implements SpeechToTextService {

	private static final Logger log = LoggerFactory.getLogger(SpeechToTextServiceImpl.class);

	// Supported audio formats by OpenAI
	private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("flac", "m4a", "mp3", "mp4", "mpeg", "mpga", "oga",
			"ogg", "wav", "webm");

	private final WebClient webClient;
	@Value("${openai.audio.model}")
	private String audioModel;

	/**
	 * The language to decode as, matching the streaming path's default.
	 *
	 * <p>Left to auto-detection Whisper picks per request, and on a short or
	 * noisy clip it picks wrong. A wrong pick does not produce bad English; it
	 * produces confident text in, or translated from, another language.</p>
	 */
	@Value("${openai.audio.language:en}")
	private String audioLanguage;

	private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
			new com.fasterxml.jackson.databind.ObjectMapper();

	public SpeechToTextServiceImpl(@Value("${openai.api.base-url}") String baseUrl,
			@Value("${openai.api.key}") String apiKey) {
		ExchangeStrategies strategies = ExchangeStrategies.builder()
				.codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024)).build();

		HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofSeconds(60));

		this.webClient = WebClient.builder().baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey)
				.exchangeStrategies(strategies).clientConnector(new ReactorClientHttpConnector(httpClient)).build();
	}

	@Override
	public String transcribeAudio(byte[] audioBytes, String filename) {

		if (audioBytes == null || audioBytes.length == 0) {
			return "";
		}
		String safeFilename = ensureValidFilename(filename);

		LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

		body.add("file", new ByteArrayResource(audioBytes) {
			@Override
			public String getFilename() {
				return safeFilename;
			}
		});

		body.add("model", audioModel);
		// The same three settings the streaming path uses, so the two cannot
		// drift into transcribing the same audio differently.
		//
		// verbose_json carries the per-segment statistics that make it possible
		// to tell speech from the fluent sentences Whisper invents over silence;
		// temperature 0 stops it reaching for them in the first place; language
		// stops a noisy clip being decoded as some other language entirely.
		body.add("response_format", "verbose_json");
		body.add("temperature", "0");
		if (audioLanguage != null && !audioLanguage.isBlank()) {
			body.add("language", audioLanguage.trim());
		}

		try {
			String result = webClient.post().uri("/audio/transcriptions").contentType(MediaType.MULTIPART_FORM_DATA)
					.bodyValue(body).retrieve().bodyToMono(Map.class).timeout(Duration.ofSeconds(60))
					.retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(10))
							.filter(throwable -> !(throwable instanceof WebClientResponseException.BadRequest)))
					.map(resp -> TranscriptionSanitizer.clean(objectMapper.valueToTree(resp)))
					.block();

			return result != null ? result : "";

		} catch (WebClientResponseException e) {
			log.error("Transcription request failed: {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
			return "";
		}
	}

	private String ensureValidFilename(String filename) {
		if (filename == null || filename.isEmpty()) {
			return "audio.webm";
		}

		// Extract extension from filename
		int lastDot = filename.lastIndexOf('.');
		if (lastDot > 0 && lastDot < filename.length() - 1) {
			String extension = filename.substring(lastDot + 1).toLowerCase();
			if (SUPPORTED_EXTENSIONS.contains(extension)) {
				return filename;
			}
		}

		return filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) + ".webm" : filename + ".webm";
	}

}