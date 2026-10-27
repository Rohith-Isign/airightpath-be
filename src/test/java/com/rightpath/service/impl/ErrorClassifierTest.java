package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.rightpath.dto.CodeErrorInfo;
import com.rightpath.enums.ExecutionStatus;
import com.rightpath.service.impl.CodeExecutionEngine.Language;

/**
 * Reading a crash the way a candidate needs it read.
 *
 * <p>Every one of these used to reach the exam screen as the string
 * "Runtime Error: " followed by a raw stack trace. The distinction that matters
 * under time pressure is which kind of mistake it was and on what line — an
 * index off the end of an array is a different fix from recursion that never
 * bottoms out, and neither is the same as printing the wrong answer.</p>
 */
class ErrorClassifierTest {

	private final ErrorClassifier classifier = new ErrorClassifier();

	@Test
	void javaArrayIndexIsNamedAndLocated() {
		String trace = """
				Exception in thread "main" java.lang.ArrayIndexOutOfBoundsException: Index 5 out of bounds for length 3
					at Main.main(Main.java:7)
				""";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.JAVA, 1);

		assertEquals(ExecutionStatus.RUNTIME_ERROR, error.getCategory());
		assertEquals("ArrayIndexOutOfBoundsException", error.getException());
		assertEquals(7, error.getLine());
		assertTrue(error.getMessage().contains("Index 5 out of bounds"));
		assertTrue(error.getHint().contains("past its last element"), "the hint should say what to look at");
	}

	@Test
	void javaStackOverflowIsCalledOutAsRunawayRecursion() {
		String trace = """
				Exception in thread "main" java.lang.StackOverflowError
					at Main.recurse(Main.java:4)
					at Main.recurse(Main.java:4)
				""";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.JAVA, 1);

		assertEquals("StackOverflowError", error.getException());
		assertEquals(4, error.getLine());
		assertTrue(error.getHint().contains("base case"));
	}

	@Test
	void readingMoreInputThanTheTestCaseHasIsExplained() {
		String trace = """
				Exception in thread "main" java.util.NoSuchElementException
					at java.base/java.util.Scanner.throwFor(Scanner.java:945)
					at Main.main(Main.java:6)
				""";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.JAVA, 1);

		assertEquals("NoSuchElementException", error.getException());
		// The single most common confusion in a timed exam: the code is fine, it just
		// asked for a fourth number when the case supplied three.
		assertTrue(error.getHint().contains("more input than this test case provides"));
	}

	@Test
	void divisionByZeroIsNamed() {
		String trace = "Exception in thread \"main\" java.lang.ArithmeticException: / by zero\n\tat Main.main(Main.java:3)";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.JAVA, 1);

		assertEquals("ArithmeticException", error.getException());
		assertTrue(error.getHint().contains("division by zero"));
	}

	@Test
	void pythonTracebackIsReadFromTheBottomUp() {
		String trace = """
				Traceback (most recent call last):
				  File "solution.py", line 4, in <module>
				    print(values[9])
				IndexError: list index out of range
				""";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.PYTHON, 1);

		assertEquals("IndexError", error.getException());
		assertEquals(4, error.getLine());
		assertTrue(error.getMessage().contains("list index out of range"));
		assertNotNull(error.getHint());
	}

	@Test
	void nodeReportsABlownStackAsARangeErrorAndWeTranslateIt() {
		String trace = "RangeError: Maximum call stack size exceeded\n    at repeat (solution.js:2:14)";

		CodeErrorInfo error = classifier.runtimeError(trace, Language.JAVASCRIPT, 1);

		assertEquals("StackOverflowError", error.getException(), "the candidate's mistake is recursion, whatever V8 calls it");
		assertTrue(error.getHint().contains("base case"));
	}

	@Test
	void aSegfaultIsReadFromTheExitCode() {
		CodeErrorInfo error = classifier.runtimeError("", Language.CPP, 139);

		assertEquals("SIGSEGV", error.getException());
		assertEquals("Segmentation fault", error.getMessage());
		assertTrue(error.getHint().contains("index outside an array"));
	}

	@Test
	void javacDiagnosticsGiveTheLineAndTheReason() {
		String raw = "Main.java:5: error: ';' expected\n        int x = 1\n                 ^\n1 error";

		CodeErrorInfo error = classifier.compileError(raw, Language.JAVA);

		assertEquals(ExecutionStatus.COMPILE_ERROR, error.getCategory());
		assertEquals(5, error.getLine());
		assertEquals("';' expected", error.getMessage());
		assertTrue(error.getHint().contains("no test case runs until the code compiles"));
	}

	@Test
	void pythonSyntaxErrorsAreCompileErrorsNotCrashes() {
		String raw = """
				  File "solution.py", line 2
				    if x = 1:
				         ^
				SyntaxError: invalid syntax
				""";

		CodeErrorInfo error = classifier.compileError(raw, Language.PYTHON);

		assertEquals(ExecutionStatus.COMPILE_ERROR, error.getCategory());
		assertEquals("SyntaxError", error.getException());
		assertEquals(2, error.getLine());
	}

	@Test
	void gccErrorsGiveTheLineAndTheReason() {
		String raw = "solution.c:4:5: error: expected ';' before 'return'\n    4 |     int x = 1\n      |     ^";

		CodeErrorInfo error = classifier.compileError(raw, Language.C);

		assertEquals(4, error.getLine());
		assertTrue(error.getMessage().contains("expected ';'"));
	}

	@Test
	void aTimeoutSaysWhatToLookForRatherThanJustFailing() {
		CodeErrorInfo error = classifier.runTimeout(5);

		assertEquals(ExecutionStatus.TIMEOUT, error.getCategory());
		assertTrue(error.getMessage().contains("5 seconds"));
		assertTrue(error.getHint().contains("never becomes false"));
	}

	@Test
	void outputThatDiffersOnlyInCaseIsFlaggedAsSuch() {
		CodeErrorInfo error = classifier.wrongAnswer("YES", "yes");

		assertEquals(ExecutionStatus.WRONG_ANSWER, error.getCategory());
		// Worth saying: the algorithm is right and the candidate is one character
		// away, not staring at a logic bug that isn't there.
		assertTrue(error.getHint().contains("capitalisation"));
	}

	@Test
	void outputThatDiffersOnlyInSpacingIsFlaggedAsSuch() {
		CodeErrorInfo error = classifier.wrongAnswer("1 2 3", "1\n2\n3");

		assertTrue(error.getHint().contains("spacing or line breaks"));
	}

	@Test
	void aProgramThatPrintedNothingIsToldSo() {
		CodeErrorInfo error = classifier.wrongAnswer("42", "");

		assertTrue(error.getHint().contains("printed nothing"));
	}

	@Test
	void aGenuinelyDifferentAnswerGetsNoMisleadingHint() {
		CodeErrorInfo error = classifier.wrongAnswer("42", "17");

		assertNull(error.getHint(), "inventing a near-miss where there is none would mislead");
	}

	@Test
	void anUnrecognisableCrashStillProducesSomethingReadable() {
		CodeErrorInfo error = classifier.runtimeError("", Language.C, 3);

		assertEquals(ExecutionStatus.RUNTIME_ERROR, error.getCategory());
		assertTrue(error.getMessage().contains("exit code 3"));
	}
}
