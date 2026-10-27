package com.rightpath.service.impl;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import com.rightpath.dto.AtsResultDto;
import com.rightpath.service.ATSService;

@Service
public class ATSServiceImpl implements ATSService {

    // Apache Tika for extracting text from uploaded documents
    private final Tika tika = new Tika();

    /**
     * Common English stopwords plus a few resume/JD boilerplate words. These carry
     * no matching signal, so removing them stops them from inflating the TF-IDF and
     * skill-match scores.
     */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "of", "to", "in",
            "on", "for", "with", "at", "by", "from", "as", "is", "are", "was", "were", "be",
            "been", "being", "this", "that", "these", "those", "it", "its", "we", "you",
            "they", "he", "she", "i", "will", "shall", "can", "could", "would", "should",
            "has", "have", "had", "do", "does", "did", "not", "no", "so", "than", "too",
            "very", "just", "about", "into", "over", "under", "up", "down", "out", "off",
            "more", "most", "some", "any", "all", "each", "our", "your", "their", "his",
            "her", "my", "me", "us", "them", "who", "whom", "which", "what", "when", "where",
            "why", "how", "there", "here", "also", "such", "per", "via", "etc",
            "job", "role", "work", "working", "years", "year", "experience", "candidate",
            "responsibilities", "requirements", "required", "description");

    /**
     * Lowercase, split on non-alphanumeric boundaries, and drop stopwords and
     * single-character tokens. Shared by every text-based scorer so tokenization
     * is consistent.
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() >= 2 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * Main method to process a resume and evaluate it against a job description.
     * Uses multiple scoring strategies and combines them with defined weights.
     *
     * @param resume         MultipartFile resume upload.
     * @param jobDescription Job description text.
     * @return Weighted ATS score (0–100).
     */
    /** Matches a standard email address anywhere in the resume text. */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    @Override
    public double processFiles(MultipartFile resume, String jobDescription) throws IOException, TikaException {
        String resumeText = tika.parseToString(resume.getInputStream());
        return scoreResume(resumeText, jobDescription);
    }

    /**
     * Parses the resume once, extracts its email, and computes the ATS score.
     *
     * @param resume         the uploaded resume file
     * @param jobDescription job description to score against
     * @param threshold      score at/above which the candidate is "matched"
     * @return filename, extracted email, rounded score and matched flag
     */
    public AtsResultDto screenResume(MultipartFile resume, String jobDescription, double threshold)
            throws IOException, TikaException {
        String resumeText = tika.parseToString(resume.getInputStream());
        String email = extractEmail(resumeText);
        double score = scoreResume(resumeText, jobDescription);
        double rounded = Math.round(score * 100.0) / 100.0;
        return new AtsResultDto(resume.getOriginalFilename(), email, rounded, score >= threshold);
    }

    /** Returns the first email address found in the raw resume text, or null. */
    private String extractEmail(String rawResumeText) {
        if (rawResumeText == null) {
            return null;
        }
        Matcher matcher = EMAIL_PATTERN.matcher(rawResumeText);
        return matcher.find() ? matcher.group() : null;
    }

    /** Computes the weighted ATS score (0–100) from raw resume + job-description text. */
    private double scoreResume(String rawResumeText, String rawJobDescription) {
        String resumeText = cleanText(rawResumeText);
        String jobDescription = cleanText(rawJobDescription);

        // Score breakdown
        double tfidfScore = calculateTFIDFScore(resumeText, jobDescription);
        double skillMatch = calculateSkillMatch(resumeText, jobDescription);
        double experienceMatch = calculateExperienceMatch(resumeText, jobDescription);
        double educationMatch = calculateEducationMatch(resumeText, jobDescription);

        System.out.printf("TF-IDF Score: %.2f%%\n", tfidfScore);
        System.out.printf("Skill Match: %.2f%%\n", skillMatch);
        System.out.printf("Experience Match: %.2f%%\n", experienceMatch);
        System.out.printf("Education Match: %.2f%%\n", educationMatch);

        // Weighted score calculation
        double totalScore = clamp(0.2 * tfidfScore + 0.4 * skillMatch + 0.2 * experienceMatch + 0.2 * educationMatch);
        System.out.printf("Total ATS Score: %.2f%%\n", totalScore);
        return totalScore;
    }

    // ----------------------------
    // Preprocessing & TF-IDF Logic
    // ----------------------------

    private String cleanText(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private double calculateTFIDFScore(String resume, String jobDesc) {
        List<String> resumeTokens = tokenize(resume);
        List<String> jobTokens = tokenize(jobDesc);

        if (resumeTokens.isEmpty() || jobTokens.isEmpty()) {
            return 0;
        }

        List<List<String>> allDocs = Arrays.asList(resumeTokens, jobTokens);
        Map<String, Double> resumeTfidf = computeTFIDF(resumeTokens, allDocs);
        Map<String, Double> jobTfidf = computeTFIDF(jobTokens, allDocs);

        double similarity = computeCosineSimilarity(resumeTfidf, jobTfidf) * 100;
        return clamp(similarity);
    }

    /** Constrain a percentage score to the valid 0–100 range. */
    private double clamp(double score) {
        if (Double.isNaN(score)) {
            return 0;
        }
        return Math.max(0.0, Math.min(100.0, score));
    }

    private Map<String, Double> computeTFIDF(List<String> tokens, List<List<String>> allDocuments) {
        Map<String, Double> tfidfScores = new HashMap<>();
        Map<String, Integer> termFreq = new HashMap<>();
        Map<String, Integer> docFreq = new HashMap<>();
        int totalDocs = allDocuments.size();

        for (String token : tokens) {
            termFreq.put(token, termFreq.getOrDefault(token, 0) + 1);
        }

        for (List<String> doc : allDocuments) {
            for (String token : new HashSet<>(doc)) {
                docFreq.put(token, docFreq.getOrDefault(token, 0) + 1);
            }
        }

        for (String token : tokens) {
            double tf = (double) termFreq.get(token) / tokens.size();
            double idf = Math.log((double) totalDocs / (1 + docFreq.getOrDefault(token, 0)));
            tfidfScores.put(token, tf * idf);
        }

        return tfidfScores;
    }

    private double computeCosineSimilarity(Map<String, Double> doc1, Map<String, Double> doc2) {
        double dotProduct = 0.0, norm1 = 0.0, norm2 = 0.0;

        for (String key : doc1.keySet()) {
            dotProduct += doc1.get(key) * doc2.getOrDefault(key, 0.0);
            norm1 += Math.pow(doc1.get(key), 2);
        }

        for (double value : doc2.values()) {
            norm2 += Math.pow(value, 2);
        }

        return (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    // ----------------------------
    // Skill Matching Logic
    // ----------------------------

    private double calculateSkillMatch(String resume, String jobDesc) {
        // Measure how many of the DISTINCT job keywords the resume covers.
        // Deduplicate both sides so repeated words in the resume can't push the
        // score above 100% (the previous version counted every resume token that
        // appeared in the job description, producing >100% "matches"), and drop
        // stopwords so common words don't count as skills.
        Set<String> jobSkills = new HashSet<>(tokenize(jobDesc));
        Set<String> resumeSkills = new HashSet<>(tokenize(resume));

        if (jobSkills.isEmpty()) return 0;

        long matchCount = jobSkills.stream().filter(resumeSkills::contains).count();
        return ((double) matchCount / jobSkills.size()) * 100;
    }

    // ----------------------------
    // Experience Matching Logic
    // ----------------------------

    private int extractYearsOfExperience(String text) {
        if (text.toLowerCase().contains("fresher")) return 0;

        Pattern pattern = Pattern.compile("(\\d+)\\s*(\\+)?\\s*(years?|yrs?)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);

        int maxYears = 0;
        while (matcher.find()) {
            try {
                int years = Integer.parseInt(matcher.group(1));
                maxYears = Math.max(maxYears, years);
            } catch (NumberFormatException e) {
                System.err.println("Invalid year format: " + matcher.group(1));
            }
        }

        return maxYears;
    }

    private double calculateExperienceMatch(String resume, String jobDesc) {
        int resumeExp = extractYearsOfExperience(resume);
        int jobExp = extractYearsOfExperience(jobDesc);

        System.out.printf("Extracted Resume Experience: %d years\n", resumeExp);
        System.out.printf("Required Job Experience: %d years\n", jobExp);

        if (resumeExp == 0 && jobExp == 0) return 100.0;
        if (resumeExp == 0 && jobExp > 0) return 0.0;
        if (resumeExp >= jobExp) return 100.0;

        return ((double) resumeExp / jobExp) * 100;
    }

    // ----------------------------
    // Education Matching Logic
    // ----------------------------

    private double calculateEducationMatch(String resume, String jobDesc) {
        List<String> resumeDegrees = extractDegrees(resume);
        List<String> jobDegrees = extractDegrees(jobDesc);
        List<Integer> resumeYears = extractPassingYears(resume);
        List<Integer> jobYears = extractPassingYears(jobDesc);

        if (jobDegrees.isEmpty() || (jobDegrees.size() == 1 && jobDegrees.get(0).equalsIgnoreCase("any degree"))) {
            return 100.0;
        }

        boolean degreeMatch = resumeDegrees.stream().anyMatch(jobDegrees::contains);
        if (!degreeMatch) return 0.0;

        if (jobYears.isEmpty()) return 100.0;

        boolean yearMatch = resumeYears.stream().anyMatch(jobYears::contains);
        return yearMatch ? 100.0 : 0.0;
    }

    private List<String> extractDegrees(String text) {
        List<String> degrees = Arrays.asList(
                "bachelor", "b.sc", "b.tech", "b.com", "bba", "bca",
                "master", "m.sc", "m.tech", "mba", "mca", "phd"
        );
        List<String> found = new ArrayList<>();
        for (String deg : degrees) {
            if (text.toLowerCase().contains(deg)) {
                found.add(deg);
            }
        }
        return found;
    }

    private List<Integer> extractPassingYears(String text) {
        List<Integer> years = new ArrayList<>();
        Matcher matcher = Pattern.compile("(\\d{4})").matcher(text);

        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            if (year >= 1950 && year <= 2030) {
                years.add(year);
            }
        }

        // Handle ranges like "2018-2021"
        Matcher rangeMatcher = Pattern.compile("(\\d{4})\\s*-\\s*(\\d{4})").matcher(text);
        while (rangeMatcher.find()) {
            int start = Integer.parseInt(rangeMatcher.group(1));
            int end = Integer.parseInt(rangeMatcher.group(2));
            for (int y = start; y <= end; y++) {
                if (!years.contains(y)) years.add(y);
            }
        }

        return years;
    }

    // ----------------------------
    // Notification Logic
    // ----------------------------

    /**
     * Evaluates whether a candidate qualifies based on the ATS score.
     * Sends a notification if the score meets the threshold.
     */
    public void evaluateScoreAndNotify(String toEmail, String fullName, double atsScore) {
        if (atsScore >= 70) {
            sendShortlistNotification(toEmail, fullName);
            System.out.printf("✅ Candidate '%s' shortlisted. Email sent to %s\n", fullName, toEmail);
        } else {
            System.out.printf("❌ Candidate '%s' did not meet the threshold. ATS Score: %.2f%%\n", fullName, atsScore);
        }
    }

    /**
     * Simulated email service for sending shortlist notifications.
     * Replace with actual implementation.
     */
    public void sendShortlistNotification(String toEmail, String fullName) {
        // TODO: Replace with actual email service logic.
        System.out.printf("📧 Sending shortlist notification to %s for candidate %s\n", toEmail, fullName);
    }
}
