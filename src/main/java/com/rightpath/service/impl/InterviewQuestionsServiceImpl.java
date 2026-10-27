package com.rightpath.service.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rightpath.dto.InterviewQuestion;
import com.rightpath.dto.InterviewQuestionInfo;
import com.rightpath.entity.UploadInterviewQuestions;
import com.rightpath.exceptions.ResourceNotFoundException;
import com.rightpath.repository.InterviewQuestionsRepository;
import com.rightpath.service.InterviewQuestionsService;
import com.rightpath.service.OpenAiService;
import com.rightpath.service.StorageService;


@Service
@lombok.extern.slf4j.Slf4j
public class InterviewQuestionsServiceImpl implements InterviewQuestionsService {

    @Autowired
    private StorageService storageService; //  your existing S3 service

    @Autowired
    private InterviewQuestionsRepository repository;
    
    @Autowired
    private OpenAiService openAiService;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${questions.additional.count:100}")
    private int additionalQuestionsCount;

    private static final String PREFIX = "interview";

    public void uploadInterviewQuestions(String jobPrefix, MultipartFile file) {

        try {
            //  Step 1: Get original file name (NO generation)
            String fileName = file.getOriginalFilename();

            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("File name is missing");
            }

            //  Step 2: Upload using existing S3 service
            storageService.uploadFile(PREFIX, fileName, file);

            //  Step 3: Save SAME key in DB
            String key = PREFIX + "/" + fileName;

            UploadInterviewQuestions entity = new UploadInterviewQuestions();
            entity.setJobPrefix(jobPrefix);
            entity.setFileName(key);

            repository.save(entity);

            System.out.println("✅ Interview file uploaded & saved: " + key);

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("❌ Failed to upload interview questions");
        }
    }
    
    @Override
    public String fetchInterviewQuestions(String jobPrefix) {

        try {
            // ✅ Step 1: Get latest file from DB
            UploadInterviewQuestions entity = repository
                    .findByJobPrefixOrderByIdDesc(jobPrefix)
                    .stream()
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "No interview questions have been uploaded for this job (" + jobPrefix
                                    + "). Upload an interview question set before starting the interview."));

            //  Step 2: Extract key
            String key = entity.getFileName(); // interview/file.json

            //  Step 3: Split prefix + fileName
            String[] parts = key.split("/", 2);

            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid S3 key format");
            }

            String prefix = parts[0];   // interview
            String fileName = parts[1]; // actual file

            //  Step 4: Download from S3
            return storageService.downloadFileAsText(prefix, fileName);

        } catch (ResourceNotFoundException e) {
            // Rethrown deliberately. This used to be caught by the blanket
            // handler below and replaced with a RuntimeException, so a job with
            // no interview questions uploaded answered 500 "Something went wrong
            // on our end" — and the candidate's screen blamed their network.
            // As a 404 the message reaches the candidate and names the fix.
            throw e;
        } catch (Exception e) {
            // A genuine infrastructure failure: the record exists but the file
            // could not be read. Logged with its cause rather than printed to
            // stdout, and kept distinct from "nothing was ever uploaded".
            log.error("Could not read the interview question file for jobPrefix {}", jobPrefix, e);
            throw new IllegalStateException(
                    "The interview questions for this job could not be read. Please contact your administrator.", e);
        }
    }

    
    
    @Override
    public List<InterviewQuestionInfo> loadAndPrepareQuestions(String jobPrefix) {
        return loadAndPrepareQuestions(jobPrefix, null, null);
    }

    @Override
    public List<InterviewQuestionInfo> loadAndPrepareQuestions(String jobPrefix, Long fromDate, Long toDate) {
        String content = fetchInterviewQuestions(jobPrefix);
        ObjectMapper mapper = new ObjectMapper();
        try {
            JsonNode root = mapper.readTree(content);
            JsonNode questionsNode = root.get("questions");
            if (questionsNode == null || !questionsNode.isArray()) {
                // Iterating a null node threw a NullPointerException, which the
                // blanket catch below turned into the same opaque 500 as every
                // other failure here.
                throw new IllegalStateException(
                        "The interview question file for this job has no \"questions\" list.");
            }
            List<InterviewQuestionInfo> allQuestions = new ArrayList<>();
            for (JsonNode node : questionsNode) {
                String uniqueId = node.get("uniqueId").asText();
                String level = node.get("level").asText();
                String questionText = node.get("question").asText();
                String category = node.has("category") ? node.get("category").asText() : "General";
                Long createdAt = node.has("createdAt") ? node.get("createdAt").asLong() : null;
                allQuestions.add(new InterviewQuestionInfo(questionText, uniqueId, level, category, createdAt));
            }

            // Apply date filter if provided
            if (fromDate != null || toDate != null) {
                allQuestions = allQuestions.stream()
                    .filter(q -> {
                        Long created = q.getCreatedAt();
                        if (created == null) return false;
                        if (fromDate != null && created < fromDate) return false;
                        if (toDate != null && created > toDate) return false;
                        return true;
                    })
                    .collect(Collectors.toList());
             
            }

            return prepareInterviewQuestions(allQuestions);
        } catch (ResourceNotFoundException | IllegalStateException e) {
            // Already carries a message meant for the candidate — see
            // fetchInterviewQuestions. Wrapping it here would put it back behind
            // a generic 500.
            throw e;
        } catch (Exception e) {
            log.error("Interview question file for jobPrefix {} could not be parsed", jobPrefix, e);
            throw new IllegalStateException(
                    "The interview questions for this job are not readable. Please contact your administrator.", e);
        }
    }
    
    
    private List<InterviewQuestionInfo> prepareInterviewQuestions(List<InterviewQuestionInfo> allQuestions) {
        // Group by category and type
        Map<String, List<InterviewQuestionInfo>> technicalByCategory = new HashMap<>();
        Map<String, List<InterviewQuestionInfo>> codingByCategory = new HashMap<>();
        Map<String, List<InterviewQuestionInfo>> snippetByCategory = new HashMap<>();

        for (InterviewQuestionInfo q : allQuestions) {
            String text = q.getText();
            String category = q.getCategory();
            if (text.startsWith("[CODING]")) {
                codingByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(q);
            } else if (text.contains("code snippet")
                    || text.contains("output of the following code")
                    || text.contains("what happens in this code")) {
                snippetByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(q);
            } else {
                technicalByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(q);
            }
        }

        // Helper to pick exactly N questions from a category map
        BiFunction<Map<String, List<InterviewQuestionInfo>>, Integer, List<InterviewQuestionInfo>> pickQuestions = (categoryMap, needed) -> {
            List<InterviewQuestionInfo> result = new ArrayList<>();
            List<String> categories = new ArrayList<>(categoryMap.keySet());
            Collections.shuffle(categories);
            // First pass: pick one from each distinct category
            for (String cat : categories) {
                if (result.size() >= needed) break;
                List<InterviewQuestionInfo> catQuestions = categoryMap.get(cat);
                if (!catQuestions.isEmpty()) {
                    Collections.shuffle(catQuestions);
                    result.add(catQuestions.get(0));
                }
            }
            // If still needed, pick additional from any category (may repeat categories)
            if (result.size() < needed) {
                List<InterviewQuestionInfo> all = categoryMap.values().stream()
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
                Collections.shuffle(all);
                for (InterviewQuestionInfo q : all) {
                    if (result.size() >= needed) break;
                    if (!result.contains(q)) {
                        result.add(q);
                    }
                }
            }
            if (result.size() < needed) {
                throw new RuntimeException(String.format(
                    "Not enough questions of this type. Need %d but only found %d. Please add more questions to the bank.",
                    needed, result.size()));
            }
            return result;
        };

        // Strictly pick 7 technical, 2 coding, 2 snippet
        List<InterviewQuestionInfo> technical = pickQuestions.apply(technicalByCategory, 7);
        List<InterviewQuestionInfo> coding = pickQuestions.apply(codingByCategory, 2);
        List<InterviewQuestionInfo> snippet = pickQuestions.apply(snippetByCategory, 2);

        List<InterviewQuestionInfo> selected = new ArrayList<>();
        selected.addAll(technical);
        selected.addAll(coding);
        selected.addAll(snippet);
        Collections.shuffle(selected);

        // Add intro question (its own category)
        InterviewQuestionInfo intro = new InterviewQuestionInfo(
        	    "Tell me about yourself", "INTRO", "INTRODUCTION", "INTRODUCTION", null);
        selected.add(0, intro);

//        log.info("Successfully selected {} questions (7 technical, 2 coding, 2 snippet) with category variety.", selected.size());
        return selected;
    }

  
    @Override
    public void updateInterviewQuestionsWithAI(String jobPrefix) {
        updateInterviewQuestionsWithAI(jobPrefix, DEFAULT_CATEGORIES, additionalQuestionsCount);
    }

    // New overloaded method accepting categories and total count
    public void updateInterviewQuestionsWithAI(String jobPrefix, List<String> categories, int totalQuestions) {
     
        // 1. Get the latest file from DB
        UploadInterviewQuestions entity = repository
                .findByJobPrefixOrderByIdDesc(jobPrefix)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("No file found for jobPrefix: " + jobPrefix));

        String key = entity.getFileName();
        String[] parts = key.split("/", 2);
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid S3 key format: " + key);
        }
        String s3Prefix = parts[0];
        String fileName = parts[1];

        // 2. Download existing content
        String existingContent = storageService.downloadFileAsText(s3Prefix, fileName);

        // 3. Parse existing questions
        List<InterviewQuestion> existingQuestions = parseQuestions(existingContent);
     

        // 4. Determine next ID
        int maxId = existingQuestions.stream()
                .mapToInt(InterviewQuestion::getId)
                .max()
                .orElse(-1);
        int nextId = maxId + 1;

        // 5. Generate new questions via AI using categories
        List<InterviewQuestion> newQuestions = openAiService.generateQuestionsForCategories(
                jobPrefix, categories, totalQuestions);
        
        if (newQuestions == null || newQuestions.isEmpty()) {
          
            return;
        }
      

        // 6. Assign IDs, unique IDs, and ensure createdAt is set to current time
        long now = System.currentTimeMillis();
        for (int i = 0; i < newQuestions.size(); i++) {
            InterviewQuestion q = newQuestions.get(i);
            
            // Assign sequential ID
            q.setId(nextId + i);
            
            // Generate uniqueId if missing or blank
            if (q.getUniqueId() == null || q.getUniqueId().isBlank()) {
                q.setUniqueId(generateUniqueId(jobPrefix, nextId + i, now + i));
            }
            
            // Ensure level is set
            if (q.getLevel() == null || q.getLevel().isBlank()) {
                q.setLevel("level-2");
            }
            
            // FORCE createdAt to current timestamp (override whatever AI sent)
            q.setCreatedAt(now);
        }

        // 7. Avoid duplicates by question text
        Set<String> existingTexts = existingQuestions.stream()
                .map(InterviewQuestion::getQuestion)
                .collect(Collectors.toSet());
        
        List<InterviewQuestion> filtered = newQuestions.stream()
                .filter(q -> !existingTexts.contains(q.getQuestion()))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
         
            return;
        }

        // 8. Merge existing and new questions
        List<InterviewQuestion> allQuestions = new ArrayList<>(existingQuestions);
        allQuestions.addAll(filtered);

        // 9. Serialize back to JSON preserving structure
        String updatedContent = serializeQuestions(existingContent, allQuestions);

        // 10. Upload back to S3
        storageService.uploadStringContent(s3Prefix, fileName, updatedContent);
   
    }
    
    private List<InterviewQuestion> parseQuestions(String jsonContent) {
        try {
            JsonNode root = objectMapper.readTree(jsonContent);
            JsonNode questionsNode;
            if (root.isArray()) {
                questionsNode = root;
            } else if (root.has("questions")) {
                questionsNode = root.get("questions");
            } else {
                throw new IllegalArgumentException("Invalid JSON format: no 'questions' array found");
            }

            List<InterviewQuestion> questions = new ArrayList<>();
            for (JsonNode node : questionsNode) {
                InterviewQuestion q = new InterviewQuestion();
                if (node.has("id")) q.setId(node.get("id").asInt());
                if (node.has("uniqueId")) q.setUniqueId(node.get("uniqueId").asText());
                if (node.has("level")) q.setLevel(node.get("level").asText());
                if (node.has("question")) q.setQuestion(node.get("question").asText());
                if (node.has("createdAt")) q.setCreatedAt(node.get("createdAt").asLong());
                questions.add(q);
            }
            return questions;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }

    private String serializeQuestions(String originalJson, List<InterviewQuestion> questions) {
        try {
            JsonNode root = objectMapper.readTree(originalJson);
            ArrayNode questionsArray = objectMapper.valueToTree(questions);
            if (root.isArray()) {
                return objectMapper.writeValueAsString(questionsArray);
            } else if (root.has("questions")) {
                ObjectNode newRoot = objectMapper.createObjectNode();
                // Copy all fields except "questions"
                root.fields().forEachRemaining(entry -> {
                    if (!"questions".equals(entry.getKey())) {
                        newRoot.set(entry.getKey(), entry.getValue());
                    }
                });
                newRoot.set("questions", questionsArray);
                return objectMapper.writeValueAsString(newRoot);
            } else {
                throw new IllegalArgumentException("Invalid JSON format: no 'questions' array found");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize JSON", e);
        }
    }

    private String generateUniqueId(String jobPrefix, int id, long timestamp) {
        String safePrefix = jobPrefix.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return String.format("L2-%s-001-001-%d", safePrefix, timestamp);
    }
    
    private static final List<String> DEFAULT_CATEGORIES = List.of(
            "OOP", "ExceptionHandling", "Multithreading", "Collections",
            "Java8", "JVM", "I_O", "Generics", "DesignPatterns", "JDBC"
        );
    
    
}