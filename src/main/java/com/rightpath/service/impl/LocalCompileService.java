package com.rightpath.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.rightpath.dto.CompileRequest;
import com.rightpath.dto.CompileResponse;
import com.rightpath.service.CompileService;
import com.rightpath.service.impl.CodeExecutionEngine.CaseOutcome;
import com.rightpath.service.impl.CodeExecutionEngine.ExecutionReport;
import com.rightpath.service.impl.CodeExecutionEngine.RunCase;

/**
 * The interview's "Compile &amp; Run", on the same engine as the coding assessment.
 *
 * <p>This used to be a second, separate runner that could only build Java: a Python or C++ answer came back "Only
 * Java compilation is supported at this time", while the very same code ran fine in the assessment. It also wrote
 * every candidate's file into one shared folder named after the class, so two candidates running a class called
 * {@code Main} at once could run each other's code. The engine behind the assessment already handles Java, Python,
 * JavaScript, C and C++, in a working directory of its own for every run and with limits on time and output, so this
 * now simply uses it.</p>
 */
@Service
public class LocalCompileService implements CompileService {

	private final CodeExecutionEngine engine;

	public LocalCompileService(CodeExecutionEngine engine) {
		this.engine = engine;
	}

	@Override
	public CompileResponse compile(CompileRequest request) {
		CompileResponse response = new CompileResponse();
		String stdin = request.getStdin() == null ? "" : request.getStdin();

		ExecutionReport report = engine.execute(request.getCode(), request.getLanguage(),
				List.of(RunCase.freeRun(stdin)));
		response.setExecutionTimeMs(report.totalMs());

		if (report.compileError() != null) {
			response.setError(report.compileError().getMessage());
			return response;
		}

		CaseOutcome outcome = report.outcomes().get(0);
		response.setOutput(outcome.output());
		// A run that crashed or timed out has output and an error; both go back, the error being what explains why
		// the output stops where it does.
		if (outcome.error() != null) {
			response.setError(outcome.error().getMessage());
		}
		return response;
	}
}
