package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.enums.ExecutionStatus;
import com.rightpath.exceptions.UnsupportedLanguageException;
import com.rightpath.service.impl.CodeExecutionEngine.ExecutionReport;
import com.rightpath.service.impl.CodeExecutionEngine.RunCase;

/**
 * The execution engine against real code, really compiled and really run.
 *
 * <p>These are the guarantees the exam depends on: an answer comes back in
 * seconds whatever the candidate wrote, the wait does not grow with the number
 * of test cases, and every way code can fail arrives as a distinct, named
 * status rather than a stack trace with "Runtime Error" in front of it.</p>
 *
 * <p>Skipped where the JDK has no system compiler, since there is nothing to
 * compile Java with.</p>
 */
@EnabledIf("javaToolchainAvailable")
class CodeExecutionEngineTest {

	private CodeExecutionEngine engine;

	static boolean javaToolchainAvailable() {
		return javax.tools.ToolProvider.getSystemJavaCompiler() != null;
	}

	@BeforeEach
	void setUp() {
		engine = new CodeExecutionEngine(new ErrorClassifier());
		ReflectionTestUtils.setField(engine, "compileTimeoutSeconds", 20);
		ReflectionTestUtils.setField(engine, "runTimeoutSeconds", 5);
		ReflectionTestUtils.setField(engine, "totalTimeoutSeconds", 45);
		ReflectionTestUtils.setField(engine, "maxOutputBytes", 65536);
		ReflectionTestUtils.setField(engine, "maxScriptLength", 200000);
		ReflectionTestUtils.setField(engine, "maxParallelRuns", 4);
		ReflectionTestUtils.setField(engine, "javaCommand", "java");
		ReflectionTestUtils.setField(engine, "javacCommand", "javac");
	}

	@Test
	void aCorrectSolutionPassesEveryCase() {
		String echo = """
				import java.util.Scanner;
				public class Main {
				    public static void main(String[] args) {
				        Scanner sc = new Scanner(System.in);
				        int a = sc.nextInt();
				        int b = sc.nextInt();
				        System.out.println(a + b);
				    }
				}
				""";

		ExecutionReport report = engine.execute(echo, "java",
				List.of(new RunCase("1 2", "3"), new RunCase("10 20", "30"), new RunCase("-5 5", "0")));

		assertEquals(ExecutionStatus.PASSED, report.status());
		assertEquals(3, report.passedCount());
	}

	@Test
	void manyTestCasesCostOneCompileNotOnePerCase() {
		String constant = """
				public class Main {
				    public static void main(String[] args) { System.out.println("ok"); }
				}
				""";

		List<RunCase> one = List.of(new RunCase("", "ok"));
		ExecutionReport single = engine.execute(constant, "java", one);

		List<RunCase> eight = List.of(new RunCase("", "ok"), new RunCase("", "ok"), new RunCase("", "ok"),
				new RunCase("", "ok"), new RunCase("", "ok"), new RunCase("", "ok"), new RunCase("", "ok"),
				new RunCase("", "ok"));
		ExecutionReport many = engine.execute(constant, "java", eight);

		assertEquals(ExecutionStatus.PASSED, many.status());
		assertEquals(8, many.passedCount());
		// The old code compiled once per case, so eight cases cost eight javac runs
		// and the wait grew with the paper. Compiling once means the extra seven are
		// only their own runtime - nowhere near eight times the single-case cost.
		assertTrue(many.totalMs() < single.totalMs() * 5,
				"8 cases took " + many.totalMs() + "ms against " + single.totalMs() + "ms for 1");
	}

	@Test
	void anInfiniteLoopComesBackInSecondsNotMinutes() {
		String spin = """
				public class Main {
				    public static void main(String[] args) { while (true) { } }
				}
				""";

		ReflectionTestUtils.setField(engine, "runTimeoutSeconds", 2);
		long startedAt = System.currentTimeMillis();
		ExecutionReport report = engine.execute(spin, "java", List.of(new RunCase("", "anything")));
		long elapsed = System.currentTimeMillis() - startedAt;

		assertEquals(ExecutionStatus.TIMEOUT, report.status());
		assertEquals(ExecutionStatus.TIMEOUT, report.outcomes().get(0).status());
		// This is the whole point: unbounded waitFor() used to hold the request open
		// until something upstream gave up minutes later.
		assertTrue(elapsed < 20_000, "took " + elapsed + "ms, which is not a fast answer");
		assertTrue(report.outcomes().get(0).error().getMessage().contains("2 seconds"));
	}

	@Test
	void codeThatDoesNotCompileFailsOnceWithTheLineNumber() {
		String broken = """
				public class Main {
				    public static void main(String[] args) {
				        int x = 1
				        System.out.println(x);
				    }
				}
				""";

		ExecutionReport report = engine.execute(broken, "java",
				List.of(new RunCase("", "1"), new RunCase("", "1"), new RunCase("", "1")));

		assertEquals(ExecutionStatus.COMPILE_ERROR, report.status());
		assertNotNull(report.compileError());
		assertEquals(3, report.compileError().getLine());
		assertTrue(report.compileError().getMessage().contains("';'"));
		// Every case reports the compile error rather than being silently dropped,
		// and the inputs survive so the results table still lines up.
		assertEquals(3, report.outcomes().size());
		assertTrue(report.outcomes().stream().allMatch(o -> o.status() == ExecutionStatus.COMPILE_ERROR));
	}

	@Test
	void anArrayIndexCrashIsReportedAsOne() {
		String overrun = """
				public class Main {
				    public static void main(String[] args) {
				        int[] values = new int[3];
				        System.out.println(values[5]);
				    }
				}
				""";

		ExecutionReport report = engine.execute(overrun, "java", List.of(new RunCase("", "0")));

		assertEquals(ExecutionStatus.RUNTIME_ERROR, report.status());
		assertEquals("ArrayIndexOutOfBoundsException", report.outcomes().get(0).error().getException());
		assertEquals(4, report.outcomes().get(0).error().getLine());
	}

	@Test
	void runawayRecursionIsReportedAsAStackOverflow() {
		String recursive = """
				public class Main {
				    static int depth(int n) { return depth(n + 1); }
				    public static void main(String[] args) { System.out.println(depth(0)); }
				}
				""";

		ExecutionReport report = engine.execute(recursive, "java", List.of(new RunCase("", "0")));

		assertEquals(ExecutionStatus.RUNTIME_ERROR, report.status());
		assertEquals("StackOverflowError", report.outcomes().get(0).error().getException());
		assertTrue(report.outcomes().get(0).error().getHint().contains("base case"));
	}

	@Test
	void workingCodeWithTheWrongAnswerIsNotCalledAnError() {
		String off = """
				public class Main {
				    public static void main(String[] args) { System.out.println(41); }
				}
				""";

		ExecutionReport report = engine.execute(off, "java", List.of(new RunCase("", "42")));

		assertEquals(ExecutionStatus.WRONG_ANSWER, report.status());
		assertEquals("41", report.outcomes().get(0).output());
		assertEquals(0, report.passedCount());
	}

	@Test
	void aCrashOnOneCaseDoesNotStopTheRest() {
		String failsOnZero = """
				import java.util.Scanner;
				public class Main {
				    public static void main(String[] args) {
				        int n = new Scanner(System.in).nextInt();
				        System.out.println(100 / n);
				    }
				}
				""";

		ExecutionReport report = engine.execute(failsOnZero, "java",
				List.of(new RunCase("10", "10"), new RunCase("0", "0"), new RunCase("4", "25")));

		assertEquals(ExecutionStatus.PASSED, report.outcomes().get(0).status());
		assertEquals(ExecutionStatus.RUNTIME_ERROR, report.outcomes().get(1).status());
		assertEquals(ExecutionStatus.PASSED, report.outcomes().get(2).status());
		assertEquals(2, report.passedCount(), "a candidate should see which cases they did get right");
		assertEquals(ExecutionStatus.RUNTIME_ERROR, report.status(), "the crash is what to lead with");
	}

	@Test
	void trailingWhitespaceDoesNotFailAWorkingSolution() {
		String padded = """
				public class Main {
				    public static void main(String[] args) { System.out.println("42   "); }
				}
				""";

		ExecutionReport report = engine.execute(padded, "java", List.of(new RunCase("", "42")));

		assertEquals(ExecutionStatus.PASSED, report.status(), "a stray space is not a wrong answer");
	}

	@Test
	void aRunawayPrintIsCappedRatherThanFillingMemory() {
		String noisy = """
				public class Main {
				    public static void main(String[] args) {
				        for (int i = 0; i < 2_000_000; i++) { System.out.println("filling the buffer " + i); }
				    }
				}
				""";

		ReflectionTestUtils.setField(engine, "maxOutputBytes", 4096);
		ReflectionTestUtils.setField(engine, "runTimeoutSeconds", 10);
		ExecutionReport report = engine.execute(noisy, "java", List.of(new RunCase("", "nothing like this")));

		// Either the cap or the deadline stops it; what must not happen is the whole
		// stream being buffered into the server's heap.
		assertTrue(report.status() == ExecutionStatus.OUTPUT_LIMIT_EXCEEDED
				|| report.status() == ExecutionStatus.TIMEOUT, "was " + report.status());
		assertTrue(report.outcomes().get(0).output().length() <= 8192,
				"captured " + report.outcomes().get(0).output().length() + " characters");
	}

	@Test
	void aClassThatIsNotPublicStillCompiles() {
		// Candidates paste all sorts of things; refusing to find a public class used
		// to fail the run outright with "Could not find public class".
		String nonPublic = """
				class Solution {
				    public static void main(String[] args) { System.out.println("ran"); }
				}
				""";

		ExecutionReport report = engine.execute(nonPublic, "java", List.of(new RunCase("", "ran")));

		assertEquals(ExecutionStatus.PASSED, report.status());
	}

	@Test
	void noTestCasesMeansASingleFreeRunThatIsNeverWrong() {
		String hello = """
				public class Main {
				    public static void main(String[] args) { System.out.println("hello"); }
				}
				""";

		ExecutionReport report = engine.execute(hello, "java", List.of());

		assertEquals(1, report.outcomes().size());
		assertEquals(ExecutionStatus.PASSED, report.status());
		assertEquals("hello", report.outcomes().get(0).output());
	}

	@Test
	void anEmptySubmissionIsRejectedBeforeAnythingIsSpentOnIt() {
		ExecutionReport report = engine.execute("   ", "java", List.of(new RunCase("", "x")));

		assertEquals(ExecutionStatus.COMPILE_ERROR, report.status());
		assertTrue(report.compileError().getMessage().contains("No code"));
	}

	@Test
	void anUnknownLanguageSaysWhatWouldHaveWorked() {
		UnsupportedLanguageException thrown = assertThrows(UnsupportedLanguageException.class,
				() -> engine.execute("print('hi')", "cobol", List.of()));

		assertTrue(thrown.getMessage().contains("java"));
	}
}
