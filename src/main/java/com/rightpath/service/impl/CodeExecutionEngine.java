package com.rightpath.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.rightpath.dto.CodeErrorInfo;
import com.rightpath.enums.ExecutionStatus;
import com.rightpath.exceptions.CompilerToolchainException;
import com.rightpath.exceptions.UnsupportedLanguageException;

import jakarta.annotation.PreDestroy;

/**
 * Compiles a submission once, then runs it against every test case.
 *
 * <p>The shape of this class is the fix for two problems. Compilation used to
 * happen per test case, so a ten-case question paid for ten {@code javac} runs
 * and the cost grew with the length of the candidate's code; here it is paid
 * once and the compiled artifact is reused. And nothing used to bound a run at
 * all — a candidate's unterminated loop held the request open until something
 * upstream gave up minutes later; here every process has a deadline and the
 * whole submission has a budget, so the answer comes back in seconds either
 * way.</p>
 *
 * <p>Everything a candidate's code can do wrong — failing to compile, crashing,
 * spinning forever, printing endlessly, printing the wrong thing — is a normal
 * result carrying a {@link ExecutionStatus}, not an exception. Exceptions are
 * reserved for the platform failing: a missing toolchain or an unreadable
 * working directory.</p>
 */
@Component
public class CodeExecutionEngine {

    private static final Logger logger = LoggerFactory.getLogger(CodeExecutionEngine.class);

    private static final String JAVA_DEFAULT_CLASS = "Main";

    /** How long the compile step may take before it is treated as a failure. */
    @Value("${compiler.compile-timeout-seconds:20}")
    private int compileTimeoutSeconds;

    /** How long one test case may run. This is what turns an infinite loop into a fast answer. */
    @Value("${compiler.run-timeout-seconds:5}")
    private int runTimeoutSeconds;

    /** Ceiling on compile plus every run, so a many-case paper cannot add up to minutes. */
    @Value("${compiler.total-timeout-seconds:45}")
    private int totalTimeoutSeconds;

    /** Captured output per run. Beyond this the stream is drained but discarded. */
    @Value("${compiler.max-output-bytes:65536}")
    private int maxOutputBytes;

    /** Rejects a paste far larger than any exam answer before spending a compile on it. */
    @Value("${compiler.max-script-length:200000}")
    private int maxScriptLength;

    /** Test cases executed at once. Runs are independent, so this is pure latency saved. */
    @Value("${compiler.max-parallel-runs:4}")
    private int maxParallelRuns;

    @Value("${compiler.command.python:python3}")
    private String pythonCommand;

    @Value("${compiler.command.node:node}")
    private String nodeCommand;

    @Value("${compiler.command.gcc:gcc}")
    private String gccCommand;

    @Value("${compiler.command.gpp:g++}")
    private String gppCommand;

    @Value("${compiler.command.javac:javac}")
    private String javacCommand;

    @Value("${compiler.command.java:java}")
    private String javaCommand;

    private final ErrorClassifier errorClassifier;

    /** Drains stdout and feeds stdin; unbounded because every task is short and process-bound. */
    private final ExecutorService ioPool = Executors.newCachedThreadPool(daemonFactory("code-exec-io-"));

    private ExecutorService runPool;

    public CodeExecutionEngine(ErrorClassifier errorClassifier) {
        this.errorClassifier = errorClassifier;
    }

    private ExecutorService runPool() {
        // Built lazily so the configured parallelism is available; @Value fields are
        // not injected until after construction.
        if (runPool == null) {
            synchronized (this) {
                if (runPool == null) {
                    runPool = Executors.newFixedThreadPool(Math.max(1, maxParallelRuns),
                            daemonFactory("code-exec-run-"));
                }
            }
        }
        return runPool;
    }

    @PreDestroy
    void shutdown() {
        ioPool.shutdownNow();
        if (runPool != null) {
            runPool.shutdownNow();
        }
    }

    /**
     * Compiles the submission and runs it against every case.
     *
     * @param script   the candidate's source
     * @param language the language it is written in
     * @param cases    the cases to run; an empty list means a single free run with no input
     * @return one outcome per case, in the order given, plus the overall verdict
     */
    public ExecutionReport execute(String script, String language, List<RunCase> cases) {
        long startedAt = System.nanoTime();

        if (script == null || script.isBlank()) {
            return ExecutionReport.failedToCompile(
                    errorClassifier.platformError("No code was submitted.", null), cases, elapsedMs(startedAt));
        }
        if (script.length() > maxScriptLength) {
            return ExecutionReport.failedToCompile(errorClassifier.platformError(
                    "Submission is " + script.length() + " characters, over the " + maxScriptLength
                            + " character limit.",
                    null), cases, elapsedMs(startedAt));
        }

        Language spec = Language.of(language);
        List<RunCase> effectiveCases = cases.isEmpty() ? List.of(RunCase.freeRun("")) : cases;

        Path workDir;
        try {
            workDir = Files.createTempDirectory("rp-exec-");
        } catch (IOException e) {
            throw new CompilerToolchainException("Could not create a working directory for compilation.", e);
        }

        try {
            return compileAndRun(script, spec, effectiveCases, workDir, startedAt);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private ExecutionReport compileAndRun(String script, Language spec, List<RunCase> cases, Path workDir,
            long startedAt) {

        String sourceName = spec.sourceFileName(script);
        Path sourceFile = workDir.resolve(sourceName);
        try {
            Files.writeString(sourceFile, script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CompilerToolchainException("Could not write the submission to disk.", e);
        }

        // One compile (or syntax check) for the whole submission, not one per case.
        CompileOutcome compilation = compile(spec, script, workDir, sourceFile, sourceName);
        if (compilation.error() != null) {
            logger.debug("Submission failed to compile: {}", compilation.error().getMessage());
            return ExecutionReport.failedToCompile(compilation.error(), cases, elapsedMs(startedAt));
        }

        List<CaseOutcome> outcomes = runAll(compilation.runCommand(), cases, workDir, sourceName, spec, startedAt);
        return ExecutionReport.of(outcomes, elapsedMs(startedAt));
    }

    /**
     * Compiles, or for interpreted languages checks syntax, once.
     *
     * <p>Checking Python and JavaScript syntax up front costs one process and
     * saves running a doomed script against every case — and it is what lets a
     * syntax error come back as a syntax error rather than N identical crashes.</p>
     */
    private CompileOutcome compile(Language spec, String script, Path workDir, Path sourceFile, String sourceName) {
        switch (spec) {
            case JAVA -> {
                String className = stripExtension(sourceName);
                CodeErrorInfo error = compileJava(sourceFile, workDir, sourceName);
                if (error != null) {
                    return CompileOutcome.failed(error);
                }
                return CompileOutcome.ok(new String[] { javaCommand, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                        "-cp", workDir.toString(), className });
            }
            case PYTHON -> {
                CodeErrorInfo error = syntaxCheck(
                        new String[] { pythonCommand, "-m", "py_compile", sourceFile.toString() }, spec, workDir,
                        sourceName);
                return error != null ? CompileOutcome.failed(error)
                        : CompileOutcome.ok(new String[] { pythonCommand, sourceFile.toString() });
            }
            case JAVASCRIPT -> {
                CodeErrorInfo error = syntaxCheck(new String[] { nodeCommand, "--check", sourceFile.toString() }, spec,
                        workDir, sourceName);
                return error != null ? CompileOutcome.failed(error)
                        : CompileOutcome.ok(new String[] { nodeCommand, sourceFile.toString() });
            }
            case C, CPP -> {
                Path binary = workDir.resolve("program");
                String compilerBinary = spec == Language.C ? gccCommand : gppCommand;
                ProcessRun run = runProcess(new String[] { compilerBinary, "-O2", "-o", binary.toString(),
                        sourceFile.toString() }, null, compileTimeoutSeconds, workDir);
                if (run.timedOut()) {
                    return CompileOutcome.failed(errorClassifier.compileTimeout(compileTimeoutSeconds));
                }
                if (run.exitCode() != 0) {
                    return CompileOutcome.failed(
                            errorClassifier.compileError(sanitize(run.output(), workDir, sourceName), spec));
                }
                return CompileOutcome.ok(new String[] { binary.toString() });
            }
            default -> throw new UnsupportedLanguageException("Unsupported language: " + spec);
        }
    }

    /**
     * Compiles Java in-process where the JDK allows it.
     *
     * <p>Starting a whole second JVM just to run {@code javac} is most of the wait
     * on a Java submission. The in-process compiler skips that; annotation
     * processing is disabled so compiling never runs candidate-supplied code.
     * A JRE-only runtime has no system compiler, so the external binary remains
     * the fallback.</p>
     */
    private CodeErrorInfo compileJava(Path sourceFile, Path workDir, String sourceName) {
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            ProcessRun run = runProcess(
                    new String[] { javacCommand, "-encoding", "UTF-8", "-proc:none", "-d", workDir.toString(),
                            sourceFile.toString() },
                    null, compileTimeoutSeconds, workDir);
            if (run.timedOut()) {
                return errorClassifier.compileTimeout(compileTimeoutSeconds);
            }
            return run.exitCode() == 0 ? null
                    : errorClassifier.compileError(sanitize(run.output(), workDir, sourceName), Language.JAVA);
        }

        javax.tools.DiagnosticCollector<javax.tools.JavaFileObject> diagnostics = new javax.tools.DiagnosticCollector<>();
        try (javax.tools.StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {

            fileManager.setLocation(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(workDir.toFile()));
            boolean ok = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                    fileManager.getJavaFileObjects(sourceFile.toFile())).call();

            if (ok) {
                return null;
            }
            StringBuilder raw = new StringBuilder();
            diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)
                    .forEach(d -> raw.append(sourceName).append(':').append(d.getLineNumber()).append(": error: ")
                            .append(d.getMessage(Locale.ROOT)).append('\n'));
            if (raw.length() == 0) {
                diagnostics.getDiagnostics()
                        .forEach(d -> raw.append(d.getMessage(Locale.ROOT)).append('\n'));
            }
            return errorClassifier.compileError(sanitize(raw.toString(), workDir, sourceName), Language.JAVA);

        } catch (IOException e) {
            throw new CompilerToolchainException("Java compiler could not be initialised.", e);
        }
    }

    private CodeErrorInfo syntaxCheck(String[] command, Language spec, Path workDir, String sourceName) {
        ProcessRun run = runProcess(command, null, compileTimeoutSeconds, workDir);
        if (run.timedOut()) {
            return errorClassifier.compileTimeout(compileTimeoutSeconds);
        }
        if (run.exitCode() == 0) {
            return null;
        }
        return errorClassifier.compileError(sanitize(run.output(), workDir, sourceName), spec);
    }

    /**
     * Runs every case against the compiled artifact, in parallel and under a
     * shared deadline.
     *
     * <p>Cases are independent and the artifact is read-only, so running them at
     * once is latency saved for free. Any case still unfinished when the budget
     * runs out is reported as not run rather than silently dropped.</p>
     */
    private List<CaseOutcome> runAll(String[] runCommand, List<RunCase> cases, Path workDir, String sourceName,
            Language spec, long startedAt) {

        List<Callable<CaseOutcome>> tasks = new ArrayList<>(cases.size());
        for (RunCase testCase : cases) {
            tasks.add(() -> runOne(runCommand, testCase, workDir, sourceName, spec));
        }

        long remainingMs = Math.max(1, totalTimeoutSeconds * 1000L - elapsedMs(startedAt));
        List<CaseOutcome> outcomes = new ArrayList<>(cases.size());
        try {
            List<Future<CaseOutcome>> futures = runPool().invokeAll(tasks, remainingMs, TimeUnit.MILLISECONDS);
            for (int i = 0; i < futures.size(); i++) {
                outcomes.add(resolve(futures.get(i), cases.get(i)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompilerToolchainException("Execution was interrupted before it finished.", e);
        }
        return outcomes;
    }

    private CaseOutcome resolve(Future<CaseOutcome> future, RunCase testCase) {
        try {
            return future.get();
        } catch (CancellationException e) {
            return new CaseOutcome(testCase, ExecutionStatus.NOT_RUN, "", errorClassifier.budgetExhausted(
                    totalTimeoutSeconds), 0L);
        } catch (ExecutionException e) {
            logger.error("Test case execution failed unexpectedly", e.getCause());
            return new CaseOutcome(testCase, ExecutionStatus.INTERNAL_ERROR, "",
                    errorClassifier.platformError("The platform could not run this test case.", e.getCause()), 0L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CaseOutcome(testCase, ExecutionStatus.NOT_RUN, "",
                    errorClassifier.budgetExhausted(totalTimeoutSeconds), 0L);
        }
    }

    private CaseOutcome runOne(String[] runCommand, RunCase testCase, Path workDir, String sourceName, Language spec) {
        ProcessRun run = runProcess(runCommand, testCase.input(), runTimeoutSeconds, workDir);
        String output = sanitize(run.output(), workDir, sourceName);

        if (run.timedOut()) {
            return new CaseOutcome(testCase, ExecutionStatus.TIMEOUT, output,
                    errorClassifier.runTimeout(runTimeoutSeconds), run.durationMs());
        }
        if (run.truncated()) {
            return new CaseOutcome(testCase, ExecutionStatus.OUTPUT_LIMIT_EXCEEDED, output,
                    errorClassifier.outputLimit(maxOutputBytes), run.durationMs());
        }
        if (run.exitCode() != 0) {
            return new CaseOutcome(testCase, ExecutionStatus.RUNTIME_ERROR, output,
                    errorClassifier.runtimeError(output, spec, run.exitCode()), run.durationMs());
        }

        String actual = output.trim();
        if (testCase.expectedOutput() == null) {
            // Free run: there is nothing to be right or wrong about.
            return new CaseOutcome(testCase, ExecutionStatus.PASSED, actual, null, run.durationMs());
        }
        boolean matched = outputMatches(testCase.expectedOutput(), actual);
        return new CaseOutcome(testCase, matched ? ExecutionStatus.PASSED : ExecutionStatus.WRONG_ANSWER, actual,
                matched ? null : errorClassifier.wrongAnswer(testCase.expectedOutput(), actual), run.durationMs());
    }

    /**
     * Compares expected against actual, forgiving only whitespace the console
     * added: trailing spaces on a line and a trailing newline. A candidate whose
     * logic is right should not fail on a stray line ending.
     */
    static boolean outputMatches(String expected, String actual) {
        return normalise(expected).equals(normalise(actual));
    }

    private static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String[] lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line.stripTrailing()).append('\n');
        }
        // Collapse the trailing blank lines the loop above guarantees.
        int end = builder.length();
        while (end > 0 && builder.charAt(end - 1) == '\n') {
            end--;
        }
        return builder.substring(0, end);
    }

    /**
     * Starts a process, feeds it stdin and captures its output under a deadline.
     *
     * <p>stdin is written and stdout drained on separate threads. Doing either
     * inline deadlocks the moment the child fills a pipe buffer — which is what
     * made long outputs look like hangs rather than answers.</p>
     */
    ProcessRun runProcess(String[] command, String input, int timeoutSeconds, Path workDir) {
        long startedAt = System.nanoTime();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workDir.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
        } catch (IOException e) {
            throw new CompilerToolchainException(
                    "'" + command[0] + "' is not available on this server, so " + command[0]
                            + " submissions cannot be run.",
                    e);
        }

        Future<CapturedOutput> drained = ioPool.submit(() -> readCapped(process.getInputStream()));
        ioPool.submit(() -> writeStdin(process, input));

        boolean timedOut = false;
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                timedOut = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            timedOut = true;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        CapturedOutput captured = awaitOutput(drained);
        int exitCode = process.isAlive() ? -1 : process.exitValue();
        return new ProcessRun(captured.text(), exitCode, timedOut, captured.truncated(), elapsedMs(startedAt));
    }

    private CapturedOutput awaitOutput(Future<CapturedOutput> drained) {
        try {
            // The stream closes when the process dies, so this returns promptly even
            // after a forced kill; the bound is only there so a wedged reader cannot
            // hold the request.
            return drained.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CapturedOutput("", false);
        } catch (Exception e) {
            drained.cancel(true);
            return new CapturedOutput("", false);
        }
    }

    private void writeStdin(Process process, String input) {
        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null && !input.isEmpty()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                if (!input.endsWith("\n")) {
                    // Most solutions read line by line and would block without it.
                    stdin.write('\n');
                }
                stdin.flush();
            }
        } catch (IOException e) {
            // The program exited without reading its input; that is its business.
            logger.trace("stdin closed early: {}", e.getMessage());
        }
    }

    private CapturedOutput readCapped(InputStream stream) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        boolean truncated = false;
        byte[] chunk = new byte[8192];
        try (InputStream in = stream) {
            int read;
            while ((read = in.read(chunk)) != -1) {
                int room = maxOutputBytes - buffer.size();
                if (room > 0) {
                    buffer.write(chunk, 0, Math.min(read, room));
                }
                if (read > room) {
                    // Keep draining past the cap: stop reading and the child blocks
                    // on a full pipe instead of finishing.
                    truncated = true;
                }
            }
        } catch (IOException e) {
            // Killing the process closes the stream mid-read; keep what was captured.
            logger.trace("output stream closed: {}", e.getMessage());
        }
        return new CapturedOutput(buffer.toString(StandardCharsets.UTF_8), truncated);
    }

    /** Keeps server paths and temp names out of anything a candidate reads. */
    private String sanitize(String output, Path workDir, String sourceName) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        String cleaned = output.replace(workDir.toString() + java.io.File.separator, "")
                .replace(workDir.toString(), "");
        return cleaned.replace(sourceName, sourceName).trim();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
            // Daemon so a wedged child process can never hold shutdown open.
            thread.setDaemon(true);
            return thread;
        };
    }

    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            // Leaving a temp directory behind is untidy, never a reason to fail a run.
            logger.warn("Could not clean up working directory {}: {}", root, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Value types
    // -----------------------------------------------------------------------

    /** One case to run: its stdin, and the output it should produce (null to skip comparison). */
    public record RunCase(String input, String expectedOutput) {
        public static RunCase freeRun(String input) {
            return new RunCase(input, null);
        }
    }

    /** What one case did. */
    public record CaseOutcome(RunCase testCase, ExecutionStatus status, String output, CodeErrorInfo error,
            long durationMs) {

        public boolean passed() {
            return status == ExecutionStatus.PASSED;
        }
    }

    /** The whole submission's result. */
    public record ExecutionReport(ExecutionStatus status, CodeErrorInfo compileError, List<CaseOutcome> outcomes,
            long totalMs) {

        /**
         * Code that does not compile never runs, so every case carries the same
         * compile error — and the cases keep their own input and expected output so
         * the UI can still lay the table out as the candidate expects it.
         */
        static ExecutionReport failedToCompile(CodeErrorInfo error, List<RunCase> cases, long totalMs) {
            List<RunCase> shown = cases.isEmpty() ? List.of(RunCase.freeRun("")) : cases;
            List<CaseOutcome> outcomes = new ArrayList<>(shown.size());
            for (RunCase testCase : shown) {
                outcomes.add(new CaseOutcome(testCase, ExecutionStatus.COMPILE_ERROR, "", error, 0L));
            }
            return new ExecutionReport(ExecutionStatus.COMPILE_ERROR, error, outcomes, totalMs);
        }

        static ExecutionReport of(List<CaseOutcome> outcomes, long totalMs) {
            return new ExecutionReport(worstOf(outcomes), null, outcomes, totalMs);
        }

        /**
         * The verdict to lead with. A crash or a timeout says more about what to fix
         * next than a wrong answer does, so the more severe status wins.
         */
        private static ExecutionStatus worstOf(List<CaseOutcome> outcomes) {
            ExecutionStatus worst = ExecutionStatus.PASSED;
            for (CaseOutcome outcome : outcomes) {
                if (severity(outcome.status()) > severity(worst)) {
                    worst = outcome.status();
                }
            }
            return worst;
        }

        private static int severity(ExecutionStatus status) {
            return switch (status) {
                case PASSED -> 0;
                case WRONG_ANSWER -> 1;
                case OUTPUT_LIMIT_EXCEEDED -> 2;
                case NOT_RUN -> 3;
                case TIMEOUT -> 4;
                case RUNTIME_ERROR -> 5;
                case COMPILE_ERROR -> 6;
                case INTERNAL_ERROR -> 7;
            };
        }

        public long passedCount() {
            return outcomes.stream().filter(CaseOutcome::passed).count();
        }
    }

    record ProcessRun(String output, int exitCode, boolean timedOut, boolean truncated, long durationMs) {
    }

    private record CapturedOutput(String text, boolean truncated) {
    }

    /** The languages this platform can build and run, and how each maps to a source file. */
    enum Language {
        JAVA, PYTHON, JAVASCRIPT, C, CPP;

        static Language of(String language) {
            if (language == null) {
                throw new UnsupportedLanguageException("No language was given. Supported: java, python, c, cpp, javascript.");
            }
            return switch (language.trim().toLowerCase(Locale.ROOT)) {
                case "java" -> JAVA;
                case "python", "python3", "py" -> PYTHON;
                case "js", "javascript", "node" -> JAVASCRIPT;
                case "c" -> C;
                case "cpp", "c++" -> CPP;
                default -> throw new UnsupportedLanguageException(
                        "Unsupported language: " + language + ". Supported: java, python, c, cpp, javascript.");
            };
        }

        String sourceFileName(String script) {
            return switch (this) {
                case JAVA -> javaClassName(script) + ".java";
                case PYTHON -> "solution.py";
                case JAVASCRIPT -> "solution.js";
                case C -> "solution.c";
                case CPP -> "solution.cpp";
            };
        }

        /**
         * Java insists the file match the public class. A candidate who wrote a
         * non-public class, or none we can find, gets the conventional default
         * rather than a refusal to compile.
         */
        private static String javaClassName(String script) {
            var matcher = java.util.regex.Pattern
                    .compile("(?m)^\\s*public\\s+(?:final\\s+|abstract\\s+)?class\\s+(\\w+)").matcher(script);
            if (matcher.find()) {
                return matcher.group(1);
            }
            matcher = java.util.regex.Pattern.compile("(?m)^\\s*(?:final\\s+|abstract\\s+)?class\\s+(\\w+)")
                    .matcher(script);
            return matcher.find() ? matcher.group(1) : JAVA_DEFAULT_CLASS;
        }
    }

    private record CompileOutcome(String[] runCommand, CodeErrorInfo error) {
        static CompileOutcome ok(String[] runCommand) {
            return new CompileOutcome(runCommand, null);
        }

        static CompileOutcome failed(CodeErrorInfo error) {
            return new CompileOutcome(null, error);
        }
    }
}
