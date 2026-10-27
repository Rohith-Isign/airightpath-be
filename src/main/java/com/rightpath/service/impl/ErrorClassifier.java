package com.rightpath.service.impl;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.rightpath.dto.CodeErrorInfo;
import com.rightpath.enums.ExecutionStatus;
import com.rightpath.service.impl.CodeExecutionEngine.Language;

/**
 * Turns raw toolchain output into something a candidate can act on.
 *
 * <p>A stack trace is evidence, not an explanation. Under exam pressure the
 * useful answer is which of a handful of things went wrong — the code did not
 * compile, it ran off the end of an array, it recursed forever, it asked for
 * input the test case did not have — plus the line it happened on. That is what
 * this produces; the full trace travels alongside for whoever reviews the
 * attempt later.</p>
 */
@Component
public class ErrorClassifier {

    /** Java and JS name their throwables the same way: a word ending in Exception or Error. */
    private static final Pattern JVM_THROWABLE = Pattern.compile("(?:^|[\\s(])(?:[\\w$]+\\.)*([\\w$]*(?:Exception|Error))\\b");
    private static final Pattern THREAD_FRAMING = Pattern.compile("(?m)^\\s*Exception in thread \"[^\"]*\"\\s*");
    private static final Pattern JAVA_SOURCE_LINE = Pattern.compile("\\(\\w+\\.java:(\\d+)\\)");
    private static final Pattern JAVAC_ERROR = Pattern.compile("(?m)^.*?:(\\d+): error: (.+)$");
    private static final Pattern PYTHON_ERROR = Pattern.compile("(?m)^(\\w*(?:Error|Exception))(?::\\s*(.*))?$");
    private static final Pattern PYTHON_LINE = Pattern.compile("line (\\d+)");
    private static final Pattern GCC_ERROR = Pattern.compile("(?m)^.*?:(\\d+):(?:\\d+:)?\\s*error:\\s*(.+)$");
    private static final Pattern JS_LINE = Pattern.compile(":(\\d+):\\d+");

    /**
     * Plain-language causes, keyed by what the runtime called the failure.
     *
     * <p>Ordered so the more specific entries are consulted first — every entry is
     * matched on the exception's simple name.</p>
     */
    private static final Map<String, String> HINTS = new LinkedHashMap<>();
    static {
        HINTS.put("ArrayIndexOutOfBoundsException",
                "An array was read past its last element. Check the loop bound, and whether the input held as many values as the code assumed.");
        HINTS.put("StringIndexOutOfBoundsException",
                "A string was read past its last character. Check the index against the string's length.");
        HINTS.put("IndexOutOfBoundsException",
                "A list or array was read past its last element. Check the index against the collection's size.");
        HINTS.put("IndexError",
                "A list or string was read past its last element. Check the index against its length.");
        HINTS.put("StackOverflowError",
                "Recursion never reached its base case, so it called itself until the stack ran out.");
        HINTS.put("RecursionError",
                "Recursion never reached its base case, so it called itself until the stack ran out.");
        HINTS.put("OutOfMemoryError",
                "The program asked for more memory than it is allowed — usually a collection that grows without bound.");
        HINTS.put("NullPointerException",
                "Something was still null when it was used. Check that every variable and array element was assigned first.");
        HINTS.put("ArithmeticException",
                "An illegal arithmetic operation, almost always an integer division by zero.");
        HINTS.put("ZeroDivisionError", "A number was divided by zero.");
        HINTS.put("InputMismatchException",
                "The input did not have the type the code asked for — reading an int where the line held text, for instance.");
        HINTS.put("NoSuchElementException",
                "The program tried to read more input than this test case provides. Read exactly as many values as the problem specifies.");
        HINTS.put("EOFError",
                "The program tried to read more input than this test case provides. Read exactly as many values as the problem specifies.");
        HINTS.put("NumberFormatException",
                "Text that is not a number was parsed as one. Check for stray spaces or blank lines in what was read.");
        HINTS.put("ValueError",
                "A value had the wrong form for the operation — often converting text that is not a number.");
        HINTS.put("KeyError", "A dictionary was asked for a key it does not hold.");
        HINTS.put("TypeError", "An operation was applied to a value of the wrong type.");
        HINTS.put("NameError", "A name was used before it was defined. Check for a typo in a variable name.");
        HINTS.put("ReferenceError", "A name was used before it was defined. Check for a typo in a variable name.");
        HINTS.put("ClassCastException", "A value was cast to a type it does not have.");
        HINTS.put("ConcurrentModificationException",
                "A collection was modified while it was being iterated over.");
        HINTS.put("NegativeArraySizeException", "An array was created with a negative length.");
    }

    /**
     * Classifies a crash from the program's own output.
     *
     * @param output   everything the program printed, stdout and stderr merged
     * @param language the language it was written in
     * @param exitCode the process exit code, which carries the signal on Unix
     * @return the failure, categorised
     */
    public CodeErrorInfo runtimeError(String output, Language language, int exitCode) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.RUNTIME_ERROR);
        error.setType("RuntimeError");
        error.setFullTrace(output);

        switch (language) {
            case JAVA, JAVASCRIPT -> classifyJvmStyle(output, error, language);
            case PYTHON -> classifyPython(output, error);
            case C, CPP -> classifyNative(output, error, exitCode);
        }

        if (error.getMessage() == null || error.getMessage().isBlank()) {
            error.setMessage(firstMeaningfulLine(output, "The program stopped with exit code " + exitCode + "."));
        }
        if (error.getHint() == null && error.getException() != null) {
            error.setHint(HINTS.get(error.getException()));
        }
        return error;
    }

    private void classifyJvmStyle(String output, CodeErrorInfo error, Language language) {
        // "Exception in thread "main"" is framing, not the throwable — matching it
        // would name every Java crash "Exception".
        String withoutFraming = THREAD_FRAMING.matcher(output).replaceAll("");

        Matcher throwable = JVM_THROWABLE.matcher(withoutFraming);
        if (throwable.find()) {
            error.setException(throwable.group(1));
        }

        // Node reports a blown stack as a RangeError; call it what it is.
        if (output.contains("Maximum call stack size exceeded")) {
            error.setException("StackOverflowError");
        }

        String message = firstMeaningfulLine(output, null);
        if (message != null) {
            error.setMessage(message);
        }

        Matcher line = language == Language.JAVA ? JAVA_SOURCE_LINE.matcher(output) : JS_LINE.matcher(output);
        if (line.find()) {
            error.setLine(parseIntOrNull(line.group(1)));
        }
    }

    private void classifyPython(String output, CodeErrorInfo error) {
        Matcher matcher = PYTHON_ERROR.matcher(output);
        String type = null;
        String detail = null;
        while (matcher.find()) {
            // The last one is the failure itself; earlier ones are chained causes.
            type = matcher.group(1);
            detail = matcher.group(2);
        }
        if (type != null) {
            error.setException(type);
            error.setMessage(detail == null || detail.isBlank() ? type : type + ": " + detail);
        }

        Matcher line = PYTHON_LINE.matcher(output);
        Integer lastLine = null;
        while (line.find()) {
            // Deepest frame in the traceback is the candidate's own code often enough
            // to be the useful one to point at.
            lastLine = parseIntOrNull(line.group(1));
        }
        error.setLine(lastLine);
    }

    private void classifyNative(String output, CodeErrorInfo error, int exitCode) {
        String signalled = switch (exitCode) {
            case 139, -11 -> "SIGSEGV";
            case 136, -8 -> "SIGFPE";
            case 134, -6 -> "SIGABRT";
            case 137, -9 -> "SIGKILL";
            default -> null;
        };
        if (output.toLowerCase(Locale.ROOT).contains("segmentation fault")) {
            signalled = "SIGSEGV";
        }

        if (signalled != null) {
            error.setException(signalled);
            switch (signalled) {
                case "SIGSEGV" -> {
                    error.setMessage("Segmentation fault");
                    error.setHint(
                            "The program touched memory it does not own — usually an index outside an array, or a pointer that was never set.");
                }
                case "SIGFPE" -> {
                    error.setMessage("Arithmetic exception");
                    error.setHint("An illegal arithmetic operation, almost always an integer division by zero.");
                }
                case "SIGABRT" -> {
                    error.setMessage("Program aborted");
                    error.setHint("The program aborted itself — often a failed assertion or a bad memory free.");
                }
                case "SIGKILL" -> {
                    error.setMessage("Program was killed");
                    error.setHint("The program was stopped by the system, usually after using too much memory.");
                }
                default -> { /* nothing further to add */ }
            }
        }
    }

    /**
     * Classifies a compilation failure — the syntax and type errors that stop the
     * code ever running.
     *
     * @param rawOutput compiler diagnostics, already stripped of server paths
     * @param language  the language being compiled
     * @return the failure, categorised
     */
    public CodeErrorInfo compileError(String rawOutput, Language language) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.COMPILE_ERROR);
        error.setType("CompilationError");
        error.setFullTrace(rawOutput);

        switch (language) {
            case JAVA -> {
                Matcher matcher = JAVAC_ERROR.matcher(rawOutput);
                if (matcher.find()) {
                    error.setLine(parseIntOrNull(matcher.group(1)));
                    error.setMessage(matcher.group(2).trim());
                }
                error.setException("CompilationError");
            }
            case C, CPP -> {
                Matcher matcher = GCC_ERROR.matcher(rawOutput);
                if (matcher.find()) {
                    error.setLine(parseIntOrNull(matcher.group(1)));
                    error.setMessage(matcher.group(2).trim());
                }
                error.setException("CompilationError");
            }
            case PYTHON -> {
                error.setType("SyntaxError");
                error.setException("SyntaxError");
                Matcher matcher = PYTHON_ERROR.matcher(rawOutput);
                String type = null;
                String detail = null;
                while (matcher.find()) {
                    type = matcher.group(1);
                    detail = matcher.group(2);
                }
                if (type != null) {
                    error.setException(type);
                    error.setMessage(detail == null || detail.isBlank() ? type : type + ": " + detail);
                }
                Matcher line = PYTHON_LINE.matcher(rawOutput);
                if (line.find()) {
                    error.setLine(parseIntOrNull(line.group(1)));
                }
            }
            case JAVASCRIPT -> {
                error.setType("SyntaxError");
                error.setException("SyntaxError");
                Matcher throwable = JVM_THROWABLE.matcher(rawOutput);
                if (throwable.find()) {
                    error.setException(throwable.group(1));
                }
                error.setMessage(firstMeaningfulLine(rawOutput, null));
                Matcher line = JS_LINE.matcher(rawOutput);
                if (line.find()) {
                    error.setLine(parseIntOrNull(line.group(1)));
                }
            }
        }

        if (error.getMessage() == null || error.getMessage().isBlank()) {
            error.setMessage(firstMeaningfulLine(rawOutput, "The code did not compile."));
        }
        if (error.getHint() == null) {
            error.setHint("Fix the reported line, then run again — no test case runs until the code compiles.");
        }
        return error;
    }

    /** The code ran and finished, but printed something else. */
    public CodeErrorInfo wrongAnswer(String expected, String actual) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.WRONG_ANSWER);
        error.setType("WrongAnswer");
        error.setMessage("Output did not match the expected result.");
        error.setHint(nearMissHint(expected, actual));
        return error;
    }

    /**
     * Points out the near misses worth naming.
     *
     * <p>Output that differs only in case or spacing is a formatting slip, not a
     * wrong algorithm, and saying so saves a candidate from rewriting working
     * logic.</p>
     */
    private String nearMissHint(String expected, String actual) {
        if (expected == null || actual == null) {
            return null;
        }
        if (expected.equalsIgnoreCase(actual.trim())) {
            return "The output matches apart from capitalisation.";
        }
        if (stripAllWhitespace(expected).equals(stripAllWhitespace(actual))) {
            return "The output matches apart from spacing or line breaks.";
        }
        if (actual.isBlank()) {
            return "The program printed nothing. Check that the result is printed, not only computed.";
        }
        return null;
    }

    private static String stripAllWhitespace(String value) {
        return value.replaceAll("\\s+", "");
    }

    /** A single run outran its deadline — in practice, a loop that never ends. */
    public CodeErrorInfo runTimeout(int seconds) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.TIMEOUT);
        error.setType("TimeLimitExceeded");
        error.setException("TimeLimitExceeded");
        error.setMessage("The program did not finish within " + seconds + " seconds and was stopped.");
        error.setHint(
                "Check for a loop whose condition never becomes false, or an approach too slow for the input size.");
        return error;
    }

    /** Compilation itself hung, which is the toolchain struggling rather than the code. */
    public CodeErrorInfo compileTimeout(int seconds) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.COMPILE_ERROR);
        error.setType("CompileTimeout");
        error.setException("CompileTimeout");
        error.setMessage("Compilation did not finish within " + seconds + " seconds.");
        error.setHint("Try simplifying the submission — very large generated code can take too long to compile.");
        return error;
    }

    /** The program printed more than we will capture. */
    public CodeErrorInfo outputLimit(int maxBytes) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.OUTPUT_LIMIT_EXCEEDED);
        error.setType("OutputLimitExceeded");
        error.setException("OutputLimitExceeded");
        error.setMessage("The program printed more than " + maxBytes + " bytes; the output shown is truncated.");
        error.setHint("Check for a print inside a loop that runs further than intended, or leftover debug output.");
        return error;
    }

    /** The submission's overall budget ran out before this case got its turn. */
    public CodeErrorInfo budgetExhausted(int seconds) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.NOT_RUN);
        error.setType("NotRun");
        error.setMessage("Not run: the submission used its whole " + seconds + " second budget on earlier test cases.");
        error.setHint("Speed up the cases that timed out and run again to see this one.");
        return error;
    }

    /** Our failure, not the candidate's. */
    public CodeErrorInfo platformError(String message, Throwable cause) {
        CodeErrorInfo error = new CodeErrorInfo();
        error.setCategory(ExecutionStatus.INTERNAL_ERROR);
        error.setType("InternalError");
        error.setMessage(message);
        error.setFullTrace(cause == null ? null : String.valueOf(cause));
        return error;
    }

    /**
     * The first line worth showing: traces lead with framing like
     * "Traceback (most recent call last):" that says nothing on its own.
     */
    private String firstMeaningfulLine(String output, String fallback) {
        if (output == null || output.isBlank()) {
            return fallback;
        }
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("at ") || trimmed.startsWith("Traceback")
                    || trimmed.startsWith("File \"") || trimmed.startsWith("^")) {
                continue;
            }
            return trimmed.startsWith("Exception in thread")
                    ? trimmed.replaceFirst("^Exception in thread \"[^\"]*\"\\s*", "")
                    : trimmed;
        }
        return fallback;
    }

    private static Integer parseIntOrNull(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
