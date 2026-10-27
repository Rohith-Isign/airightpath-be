package com.rightpath.service.impl;

import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rightpath.service.OpenAiVisionService;

@Service
public class OpenAiVisionServiceImpl implements OpenAiVisionService {
		
		    @Value("${openai.api.key}")
		    private String apiKey;
		
		    @Value("${openai.api.base-url:https://api.openai.com/v1}")
		    private String baseUrl;

		    /**
		     * With timeouts. The default RestTemplate waits forever, so a stalled OpenAI connection held the
		     * phone's room check open until its own browser gave up with "could not reach the room check".
		     */
		    private final RestTemplate restTemplate = buildRestTemplate();

		    private static RestTemplate buildRestTemplate() {
		        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
		                new org.springframework.http.client.SimpleClientHttpRequestFactory();
		        factory.setConnectTimeout(10_000);
		        factory.setReadTimeout(45_000);
		        return new RestTemplate(factory);
		    }
		
		    @Override
		    public String analyzeRoom(byte[] imageBytes) {
		
		        try {
		
		            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
		
		            String prompt = """
		Analyze this proctoring image from a mobile device carefully.
		
		Verify the following:
		1. Is exactly ONE person visible? (The candidate)
		2. Is a computer/laptop screen visible in the frame?
		3. Are there any other people in the room?
		4. Is the candidate using another mobile phone, headset, or books?
		5. Is there anything else suspicious that might indicate cheating?
		
		The goal is to ensure only the candidate and their system are present.
		
		Return ONLY JSON:
		{
		 "status": "VERIFIED or FAILED",
		 "reason": "Short explanation if FAILED, otherwise empty string"
		}
		""";
		
		            // Built as a tree, not formatted into a string: the prompt holds
            // newlines and quotes, and pasting it into a JSON literal produced
            // a body OpenAI rejected as unparseable.
            ObjectMapper bodyMapper = new ObjectMapper();
            ObjectNode root = bodyMapper.createObjectNode();
            root.put("model", "gpt-4o");
            root.put("max_tokens", 300);
            root.putObject("response_format").put("type", "json_object");
            ArrayNode content = root.putArray("messages").addObject().put("role", "user").putArray("content");
            content.addObject().put("type", "text").put("text", prompt);
            content.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", "data:image/jpeg;base64," + base64Image);
            String body = bodyMapper.writeValueAsString(root);

            HttpHeaders headers = new HttpHeaders();
		            headers.setContentType(MediaType.APPLICATION_JSON);
		            headers.setBearerAuth(apiKey);
		
		            HttpEntity<String> request = new HttpEntity<>(body, headers);
		
		            ResponseEntity<String> response =
		                    restTemplate.postForEntity(
		                            baseUrl + "/chat/completions",
		                            request,
		                            String.class
		                    );
		
		            ObjectMapper mapper = new ObjectMapper();
		
		            JsonNode node = mapper.readTree(response.getBody());
		
		            return node
		                    .get("choices")
		                    .get(0)
		                    .get("message")
		                    .get("content")
		                    .asText();
		
		        } catch (org.springframework.web.client.HttpStatusCodeException e) {
		            // The status and body say why: a bad key, no quota, a retired model. Without them the log
		            // read only "AI verification failed" and every cause looked the same.
		            org.slf4j.LoggerFactory.getLogger(OpenAiVisionServiceImpl.class)
		                    .error("OpenAI refused the room photo: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
		            throw new RuntimeException("AI verification failed: OpenAI answered " + e.getStatusCode(), e);
		        } catch (Exception e) {
		            throw new RuntimeException("AI verification failed", e);
		        }
		    }
		}