package com.rightpath.service.impl;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.rightpath.dto.ChatCompletionRequest;
import com.rightpath.dto.ChatCompletionResponse;
import com.rightpath.dto.CodingQuestion;
import com.rightpath.dto.InterviewQuestion;
import com.rightpath.dto.OpenAiResponse;
import com.rightpath.dto.Question;
import com.rightpath.enums.PromptType;
import com.rightpath.exceptions.AiServiceException;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.JobPromptRepository;
import com.rightpath.service.OpenAiService;
import com.rightpath.util.PromptPlaceholderResolver;

import reactor.util.retry.Retry;

@Service
public class OpenAiServiceImpl implements OpenAiService {

	private static final Logger log = LoggerFactory.getLogger(OpenAiServiceImpl.class);

	private final WebClient webClient;
	@Value("${openai.model}")
	private String model;
	@Value("${openai.question.timeout-seconds:180}")
	private int questionTimeoutSeconds;
	
	@Value("${openai.question.generation.timeout-seconds:300}")
	private int generationTimeoutSeconds;
	private final ObjectMapper objectMapper = JsonMapper.builder()
			.enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
			.build();
	private final JobPromptRepository jobPromptRepository;
	private final PromptPlaceholderResolver placeholderResolver;

	public OpenAiServiceImpl(@Qualifier("openAiWebClient") WebClient webClient,
			JobPromptRepository jobPromptRepository,
			PromptPlaceholderResolver placeholderResolver) {
		this.webClient = webClient;
		this.jobPromptRepository = jobPromptRepository;
		this.placeholderResolver = placeholderResolver;
	}

	@Override
	public String ask(String conversationHistory) {

		ChatCompletionRequest request = new ChatCompletionRequest();
		request.setModel(model);

		request.setMessages(List.of(Map.of("role", "user", "content", conversationHistory)));

		ChatCompletionResponse response = webClient.post().uri("/chat/completions").bodyValue(request).retrieve()
				.bodyToMono(ChatCompletionResponse.class).timeout(Duration.ofSeconds(90))
				.retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).filter(this::isRetryable))
				.onErrorMap(ex -> ex instanceof TimeoutException,
						ex -> new AiServiceException("AI response timed out, please retry"))
				.onErrorMap(reactor.core.Exceptions::isRetryExhausted,
						ex -> new AiServiceException("AI service unavailable after retries, please try again later", ex))
				.block();

		if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
			throw new RuntimeException("Empty response from OpenAI");
		}

		return response.getChoices().get(0).getMessage().getContent();
	}

	private boolean isRetryable(Throwable t) {
		if (t instanceof WebClientResponseException ex) {
			boolean retry = ex.getStatusCode().is5xxServerError() || ex.getStatusCode().value() == 429;
			if (retry) {
				log.warn("Retryable error from OpenAI: status={}, message={}", ex.getStatusCode().value(),
						ex.getMessage());
			}
			return retry;
		}
		return false;
	}

	@Override
	public List<Question> generateQuestions(String jobPrefix) {
		log.info("generateQuestions START - jobPrefix={}", jobPrefix);
		long start = System.currentTimeMillis();

		String prompt = jobPromptRepository.findByJobPrefixAndPromptType(jobPrefix, PromptType.APTITUDE)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Aptitude prompt is not configured for job '" + jobPrefix
								+ "'. Please configure the aptitude prompt for this job before generating questions."))
				.getPrompt();
		prompt = placeholderResolver.resolveJobPlaceholders(prompt, jobPrefix);
		log.info("generateQuestions - prompt loaded, length={}, took={}ms", prompt.length(),
				System.currentTimeMillis() - start);

		long apiStart = System.currentTimeMillis();
		log.info("generateQuestions - calling OpenAI API (model=gpt-5-mini, timeout={}s) ...", questionTimeoutSeconds);

		OpenAiResponse response = webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("model", "gpt-5-mini", "messages",
						new Object[] { Map.of("role", "user", "content", prompt) }))
				.retrieve().bodyToMono(OpenAiResponse.class)
				.timeout(Duration.ofSeconds(questionTimeoutSeconds))
				.retryWhen(Retry.backoff(2, Duration.ofSeconds(3)).filter(this::isRetryable))
				.onErrorMap(ex -> ex instanceof TimeoutException,
						ex -> new AiServiceException("AI question generation timed out, please retry"))
				.onErrorMap(reactor.core.Exceptions::isRetryExhausted,
						ex -> new AiServiceException("AI service unavailable after retries, please try again later", ex))
				.block();

		log.info("generateQuestions - OpenAI API responded, took={}ms", System.currentTimeMillis() - apiStart);

		if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
			log.error("generateQuestions - Empty response from OpenAI, took={}ms total",
					System.currentTimeMillis() - start);
			throw new AiServiceException("Empty response from OpenAI");
		}

		String content = cleanJson(response.getChoices().get(0).getMessage().getContent());
		log.info("generateQuestions - response content length={}", content.length());

		try {
			List<Question> questions = objectMapper.readValue(content, new TypeReference<List<Question>>() {
			});
			log.info("generateQuestions END - parsed {} questions, total took={}ms", questions.size(),
					System.currentTimeMillis() - start);
			return questions;
		} catch (Exception e) {
			log.warn("generateQuestions - Failed to parse JSON, took={}ms", System.currentTimeMillis() - start);
			throw new AiServiceException("Failed to parse AI response, please retry");
		}
	}

	@Override
	public List<CodingQuestion> generateCodingQuestions(String jobPrefix) {
		log.info("generateCodingQuestions START - jobPrefix={}", jobPrefix);
		long start = System.currentTimeMillis();

		String prompt = jobPromptRepository.findByJobPrefixAndPromptType(jobPrefix, PromptType.CODING)
				.orElseThrow(() -> new ResourceNotFoundException(
						"Coding prompt is not configured for job '" + jobPrefix
								+ "'. Please configure the coding prompt for this job before generating questions."))
				.getPrompt();
		prompt = placeholderResolver.resolveJobPlaceholders(prompt, jobPrefix);
		log.info("generateCodingQuestions - prompt loaded, length={}, took={}ms", prompt.length(),
				System.currentTimeMillis() - start);

		long apiStart = System.currentTimeMillis();
		log.info("generateCodingQuestions - calling OpenAI API (model=gpt-5, timeout={}s) ...", questionTimeoutSeconds);

		OpenAiResponse response = webClient.post().uri("/chat/completions").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("model", "gpt-5", "messages",
						new Object[] { Map.of("role", "user", "content", prompt) }))
				.retrieve().bodyToMono(OpenAiResponse.class)
				.timeout(Duration.ofSeconds(questionTimeoutSeconds))
				.retryWhen(Retry.backoff(2, Duration.ofSeconds(3)).filter(this::isRetryable))
				.onErrorMap(ex -> ex instanceof TimeoutException,
						ex -> new AiServiceException("AI coding question generation timed out, please retry"))
				.onErrorMap(reactor.core.Exceptions::isRetryExhausted,
						ex -> new AiServiceException("AI service unavailable after retries, please try again later", ex))
				.block();

		log.info("generateCodingQuestions - OpenAI API responded, took={}ms", System.currentTimeMillis() - apiStart);

		if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
			throw new AiServiceException("Empty response from OpenAI");
		}

		String content = cleanJson(response.getChoices().get(0).getMessage().getContent());
		log.info("generateCodingQuestions - response content length={}", content.length());

		try {
			List<CodingQuestion> questions = objectMapper.readValue(content,
					new TypeReference<List<CodingQuestion>>() {
					});
			log.info("generateCodingQuestions END - parsed {} questions, total took={}ms", questions.size(),
					System.currentTimeMillis() - start);
			return questions;
		} catch (Exception e) {
			log.warn("generateCodingQuestions - Failed to parse JSON, took={}ms", System.currentTimeMillis() - start);
			throw new AiServiceException("Failed to parse AI response, please retry");
		}
	}

	private String cleanJson(String content) {

		content = content.trim();

		if (content.contains("```json")) {
			content = content.substring(content.indexOf("```json") + 7);
		}

		if (content.contains("```")) {
			content = content.substring(0, content.indexOf("```"));
		}

		int firstBracket = content.indexOf("[");
		int lastBracket = content.lastIndexOf("]");

		if (firstBracket >= 0 && lastBracket > firstBracket) {
			content = content.substring(firstBracket, lastBracket + 1);
		}

		// Remove invalid JSON escape sequences like \$ that OpenAI sometimes produces. 
		// This replaces \X (where X is not a valid escape char) with just X.
		content = content.replaceAll("\\\\([^\"\\\\bfnrtu/])", "$1");

		return content;
	}
	
	 
	@Override
	public List<InterviewQuestion> generateQuestionsForCategories(String jobPrefix,
	                                                              List<String> categories,
	                                                              int totalQuestions) {
	    try {
	        int perCategory = totalQuestions / categories.size();
	        int techPerCategory = (int) Math.round(perCategory * 0.5);
	        int codingPerCategory = (int) Math.round(perCategory * 0.25);
	        int codeExpPerCategory = perCategory - techPerCategory - codingPerCategory;

	        String prompt = String.format(
	            "You are an expert Java interviewer. Generate exactly the following questions for the job role '%s' (difficulty level: medium).\n\n" +
	            "Categories: %s\n" +
	            "For each category, generate:\n" +
	            "  - %d technical questions (prefix [TECHNICAL])\n" +
	            "  - %d coding questions (prefix [CODING])\n" +
	            "  - %d code explanation questions (prefix [CODE_EXPLANATION])\n\n" +
	            "Each question must be medium difficulty (not basic, not advanced).\n" +
	            "Return a JSON array of objects, each with fields:\n" +
	            "  - id: integer (use sequential numbers starting from 0)\n" +
	            "  - uniqueId: string in format \"L2-FSD-001-001-<timestamp>\" (use current time millis as suffix)\n" +
	            "  - level: \"level-2\"\n" +
	            "  - category: the category name\n" +
	            "  - question: the full question text including the type prefix. For [CODE_EXPLANATION] questions, the question must be followed by a newline and then the code snippet (properly formatted as a code block). For example:\n" +
	            "    \"[CODE_EXPLANATION] Explain the output of this code:\\n\\npublic class Test {\\n    public static void main(String[] args) {\\n        System.out.println(\\\"Hello\\\");\\n    }\\n}\"\n\n" +
	            "IMPORTANT: Do NOT include 'createdAt' field in the JSON. I will set it myself.\n\n" +
	            "Output ONLY valid JSON, no extra text.\n\n" +
	            "Now generate the questions:",
	            jobPrefix, String.join(", ", categories), techPerCategory, codingPerCategory, codeExpPerCategory
	        );

	        String response = askWithTimeout(prompt, generationTimeoutSeconds);
	        String cleaned = cleanJson(response);
	        if (cleaned == null || cleaned.isBlank()) {
	            log.error("AI response is empty or null after cleaning for jobPrefix: {}", jobPrefix);
	            return Collections.emptyList();
	        }

	        ObjectMapper mapper = new ObjectMapper();
	        List<InterviewQuestion> newQuestions = mapper.readValue(cleaned, new TypeReference<List<InterviewQuestion>>() {});
	        if (newQuestions == null) {
	            log.warn("Parsed questions list is null for jobPrefix: {}", jobPrefix);
	            return Collections.emptyList();
	        }

	        // Don't set IDs, uniqueIds, or createdAt here - let the caller handle it
	        // Just return the questions as received from AI
	        
	        log.info("Generated {} new questions for jobPrefix: {}", newQuestions.size(), jobPrefix);
	        return newQuestions;

	    } catch (Exception e) {
	        log.error("Failed to generate questions for jobPrefix: {}", jobPrefix, e);
	        return Collections.emptyList();
	    }
	}
	
	
	 @Override
	 public String askWithTimeout(String prompt, int timeoutSeconds) {
	     ChatCompletionRequest request = new ChatCompletionRequest();
	     request.setModel(model);
	     request.setMessages(List.of(Map.of("role", "user", "content", prompt)));

	     ChatCompletionResponse response = webClient.post()
	             .uri("/chat/completions")
	             .bodyValue(request)
	             .retrieve()
	             .bodyToMono(ChatCompletionResponse.class)
	             .timeout(Duration.ofSeconds(timeoutSeconds))
	             .retryWhen(Retry.backoff(3, Duration.ofSeconds(2)).filter(this::isRetryable))
	             .onErrorMap(ex -> ex instanceof TimeoutException,
	                     ex -> new AiServiceException("AI response timed out after " + timeoutSeconds + " seconds, please retry"))
	             .onErrorMap(reactor.core.Exceptions::isRetryExhausted,
	                     ex -> new AiServiceException("AI service unavailable after retries, please try again later", ex))
	             .block();

	     if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
	         throw new RuntimeException("Empty response from OpenAI");
	     }

	     return response.getChoices().get(0).getMessage().getContent();
	 }
}
