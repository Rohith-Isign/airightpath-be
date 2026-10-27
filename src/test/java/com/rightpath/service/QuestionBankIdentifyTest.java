package com.rightpath.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QuestionBankIdentifyTest {

	@Test
	void readsJobAndTypeFromStoredName() {
		assertArrayEquals(new String[] { "BACKEND-001", "APTITUDE" },
				QuestionBankService.identify("BACKEND-001_aptitude_a_x_com_1700.json"));
		assertArrayEquals(new String[] { "JOB_2", "CODING" }, QuestionBankService.identify("JOB_2_coding_a_x_com_1.json"));
	}

	@Test
	void unnamedFilesAreNotGuessed() {
		assertNull(QuestionBankService.identify("questions.json"));
	}
}
