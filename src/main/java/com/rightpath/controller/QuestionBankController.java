package com.rightpath.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rightpath.dto.QuestionBankAssignmentDTO;
import com.rightpath.dto.QuestionBankItemDTO;
import com.rightpath.dto.QuestionBankSyncStatus;
import com.rightpath.service.QuestionBankService;

/**
 * The question bank, for admins.
 *
 * <p>Gated on the permission to assign assessments: the questions include answer keys and
 * test-case outputs, so this is not something a candidate-level permission may read.</p>
 */
@RestController
@RequestMapping("/api/question-bank")
@PreAuthorize("hasAuthority('ASSESSMENT_ASSIGN')")
public class QuestionBankController {

	private final QuestionBankService questionBankService;

	public QuestionBankController(QuestionBankService questionBankService) {
		this.questionBankService = questionBankService;
	}

	@GetMapping("/items")
	public ResponseEntity<List<QuestionBankItemDTO>> items() {
		return ResponseEntity.ok(questionBankService.listItems());
	}

	/** Every time this question was given to a candidate. */
	@GetMapping("/items/{id}/assignments")
	public ResponseEntity<List<QuestionBankAssignmentDTO>> assignments(@PathVariable Long id) {
		return ResponseEntity.ok(questionBankService.assignmentsFor(id));
	}

	/** Read the papers already in storage into the bank. Runs in the background. */
	@PostMapping("/sync")
	public ResponseEntity<QuestionBankSyncStatus> sync(Authentication authentication) {
		return ResponseEntity.ok(questionBankService.startSync(authentication.getName()));
	}

	@GetMapping("/sync-status")
	public ResponseEntity<QuestionBankSyncStatus> syncStatus() {
		return ResponseEntity.ok(questionBankService.syncStatus());
	}
}
