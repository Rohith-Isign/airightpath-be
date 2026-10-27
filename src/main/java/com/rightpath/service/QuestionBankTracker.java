package com.rightpath.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rightpath.entity.QuestionBankAssignment;
import com.rightpath.entity.QuestionBankItem;
import com.rightpath.enums.AssessmentType;
import com.rightpath.repository.QuestionBankAssignmentRepository;
import com.rightpath.repository.QuestionBankItemRepository;

/**
 * Keeps the question bank in step with what is generated and what is assigned.
 *
 * <p>Two events feed it: a question coming out of the generator (so it exists even if it is
 * never used), and a paper being handed to a candidate (so every question on it is recorded
 * against that candidate). Everything is keyed on a fingerprint of the question's wording, so
 * the same question reaching the bank by either route, or through a re-upload, is one row.</p>
 *
 * <p>Nothing here may break an assignment or a generation: callers catch what this throws,
 * because a hiccup in the bookkeeping is not a reason for a candidate to lose their exam.</p>
 */
@Service
public class QuestionBankTracker {

	private static final Logger log = LoggerFactory.getLogger(QuestionBankTracker.class);

	public static final String AI_GENERATED = "AI_GENERATED";
	public static final String S3_FILE = "S3_FILE";
	public static final String UPLOADED = "UPLOADED";

	private final QuestionBankItemRepository items;
	private final QuestionBankAssignmentRepository assignments;
	private final ObjectMapper mapper = new ObjectMapper();

	public QuestionBankTracker(QuestionBankItemRepository items, QuestionBankAssignmentRepository assignments) {
		this.items = items;
		this.assignments = assignments;
	}

	/** Generated questions: kept whether or not anyone ever receives them. Returns how many were new. */
	public int recordGenerated(String jobPrefix, AssessmentType type, String paperJson, String actor) {
		int created = 0;
		for (JsonNode question : questionsOf(paperJson)) {
			if (upsert(jobPrefix, type, question, AI_GENERATED, actor).created) {
				created++;
			}
		}
		log.info("Question bank: {} new {} question(s) kept from generation for {}", created, type, jobPrefix);
		return created;
	}

	/** A paper found in storage that no assessment points to: kept, but nobody has received it. */
	public int recordStored(String jobPrefix, AssessmentType type, String paperJson, String actor) {
		int created = 0;
		for (JsonNode question : questionsOf(paperJson)) {
			if (upsert(jobPrefix, type, question, S3_FILE, actor).created) {
				created++;
			}
		}
		return created;
	}

	/**
	 * A paper handed to one candidate: every question on it is recorded against them.
	 *
	 * @return how many assignment rows were added
	 */
	public int recordAssignment(String jobPrefix, AssessmentType type, String paperJson, Long assessmentId,
			String candidateEmail, LocalDateTime assignedAt, String actor) {
		int added = 0;
		for (JsonNode question : questionsOf(paperJson)) {
			Upserted item = upsert(jobPrefix, type, question, UPLOADED, actor);
			if (assignments.existsByItemIdAndAssessmentId(item.item.getId(), assessmentId)) {
				continue;
			}
			try {
				assignments.save(QuestionBankAssignment.builder()
						.itemId(item.item.getId())
						.assessmentId(assessmentId)
						.candidateEmail(candidateEmail)
						.jobPrefix(jobPrefix)
						.assignedAt(assignedAt)
						.build());
				added++;
			} catch (DataIntegrityViolationException alreadyThere) {
				// Two requests recorded the same pair at once; one row is what was wanted.
			}
		}
		return added;
	}

	private record Upserted(QuestionBankItem item, boolean created) {
	}

	private Upserted upsert(String jobPrefix, AssessmentType type, JsonNode question, String source, String actor) {
		String hash = fingerprint(type, question);
		var existing = items.findByJobPrefixAndAssessmentTypeAndContentHash(jobPrefix, type, hash);
		if (existing.isPresent()) {
			return new Upserted(existing.get(), false);
		}
		try {
			QuestionBankItem saved = items.save(QuestionBankItem.builder()
					.jobPrefix(jobPrefix)
					.assessmentType(type)
					.contentHash(hash)
					.content(question.toString())
					.difficulty(firstText(question, "Difficulty", "difficulty", "category"))
					.marks(question.path("marks").isInt() ? question.path("marks").asInt() : null)
					.source(source)
					.createdBy(actor)
					.createdAt(LocalDateTime.now())
					.build());
			return new Upserted(saved, true);
		} catch (DataIntegrityViolationException raced) {
			return new Upserted(items.findByJobPrefixAndAssessmentTypeAndContentHash(jobPrefix, type, hash)
					.orElseThrow(() -> raced), false);
		}
	}

	/** The questions in a paper: a bare array, or an object holding one under "questions". */
	List<JsonNode> questionsOf(String paperJson) {
		List<JsonNode> out = new ArrayList<>();
		if (paperJson == null || paperJson.isBlank()) {
			return out;
		}
		try {
			JsonNode root = mapper.readTree(paperJson);
			JsonNode list = root.isArray() ? root : root.path("questions");
			if (list.isArray()) {
				list.forEach(node -> {
					if (node.isObject()) {
						out.add(node);
					}
				});
			}
		} catch (Exception e) {
			log.warn("Question bank: a paper could not be read as questions ({})", e.getMessage());
		}
		return out;
	}

	/**
	 * What makes two questions the same question: the wording a candidate reads.
	 *
	 * <p>Not the whole JSON: ids are renumbered every time a paper is built, and a generator may add
	 * a field tomorrow, and neither makes it a different question. Aptitude is the question and its
	 * options; coding is the title and the problem statement.</p>
	 */
	static String fingerprint(AssessmentType type, JsonNode question) {
		StringBuilder text = new StringBuilder();
		if (type == AssessmentType.CODING) {
			text.append(clean(firstText(question, "title", "question"))).append('|')
					.append(clean(firstText(question, "description")));
		} else {
			text.append(clean(firstText(question, "questionText", "question"))).append('|');
			JsonNode options = question.path("options");
			if (options.isObject()) {
				Map<String, String> sorted = new TreeMap<>();
				Iterator<Map.Entry<String, JsonNode>> fields = options.fields();
				while (fields.hasNext()) {
					Map.Entry<String, JsonNode> field = fields.next();
					sorted.put(field.getKey(), field.getValue().asText(""));
				}
				sorted.values().forEach(value -> text.append(clean(value)).append('|'));
			} else if (options.isArray()) {
				options.forEach(value -> text.append(clean(value.asText(""))).append('|'));
			}
		}
		return sha256(text.toString());
	}

	/** Case, runs of whitespace and the edges of a field are not part of what a question says. */
	private static String clean(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

	private static String firstText(JsonNode node, String... names) {
		for (String name : names) {
			String value = node.path(name).asText("");
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String sha256(String text) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder();
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
