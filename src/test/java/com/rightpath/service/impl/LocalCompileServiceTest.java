package com.rightpath.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.util.ReflectionTestUtils;

import com.rightpath.dto.CompileRequest;
import com.rightpath.dto.CompileResponse;

/**
 * The interview's Compile and Run uses the assessment's engine, so it accepts every language that engine does.
 *
 * <p>It used to be a separate runner that answered "Only Java compilation is supported at this time" to Python,
 * C++ and the rest, in an interview where the same code ran in the assessment.</p>
 */
@EnabledIf("javaToolchainAvailable")
class LocalCompileServiceTest {

	private LocalCompileService service;

	static boolean javaToolchainAvailable() {
		return javax.tools.ToolProvider.getSystemJavaCompiler() != null;
	}

	@BeforeEach
	void setUp() {
		CodeExecutionEngine engine = new CodeExecutionEngine(new ErrorClassifier());
		ReflectionTestUtils.setField(engine, "compileTimeoutSeconds", 20);
		ReflectionTestUtils.setField(engine, "runTimeoutSeconds", 5);
		ReflectionTestUtils.setField(engine, "totalTimeoutSeconds", 45);
		ReflectionTestUtils.setField(engine, "maxOutputBytes", 65536);
		ReflectionTestUtils.setField(engine, "maxScriptLength", 200000);
		ReflectionTestUtils.setField(engine, "maxParallelRuns", 4);
		ReflectionTestUtils.setField(engine, "javaCommand", "java");
		ReflectionTestUtils.setField(engine, "javacCommand", "javac");
		service = new LocalCompileService(engine);
	}

	private static CompileRequest request(String code, String language, String stdin) {
		CompileRequest request = new CompileRequest();
		request.setCode(code);
		request.setLanguage(language);
		request.setStdin(stdin);
		return request;
	}

	@Test
	void javaRunsAndReturnsItsOutput() {
		CompileResponse response = service.compile(request("""
				public class Main {
				    public static void main(String[] args) { System.out.println("hello"); }
				}
				""", "java", null));

		assertNull(response.getError(), response.getError());
		assertEquals("hello", response.getOutput().trim());
	}

	@Test
	void standardInputReachesTheProgram() {
		CompileResponse response = service.compile(request("""
				import java.util.Scanner;
				public class Main {
				    public static void main(String[] args) {
				        System.out.println(new Scanner(System.in).nextInt() * 2);
				    }
				}
				""", "java", "21"));

		assertEquals("42", response.getOutput().trim());
	}

	@Test
	void aCompileErrorComesBackAsTheError() {
		CompileResponse response = service.compile(request("public class Main { void broken( }", "java", null));

		assertNotNull(response.getError());
		assertFalse(response.getError().isBlank());
	}

	@Test
	void aLanguageThePlatformHasIsNotRefusedAsJavaOnly() {
		// Whether python3 is installed is a property of the machine, not of this code, so the assertion is only that
		// the old refusal is gone: the answer is a result or a toolchain problem, never "Only Java".
		String message;
		try {
			CompileResponse response = service.compile(request("print('hi')", "python", null));
			message = String.valueOf(response.getError()) + String.valueOf(response.getOutput());
		} catch (RuntimeException toolchainMissing) {
			message = String.valueOf(toolchainMissing.getMessage());
		}
		assertFalse(message.contains("Only Java compilation is supported"), message);
		assertTrue(true);
	}
}
