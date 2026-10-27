package com.rightpath.service.impl;

import com.rightpath.dto.CompileRequest;
import com.rightpath.dto.CompileResponse;
import com.rightpath.service.CompileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class LocalCompileService implements CompileService {

    private static final Logger log = LoggerFactory.getLogger(LocalCompileService.class);
    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir") + "/code_exec/";
    private static final long TIMEOUT_SECONDS = 5;

    private String extractClassName(String code) {
        // Try to find a public class first
        Pattern publicClassPattern = Pattern.compile("public\\s+class\\s+(\\w+)");
        Matcher matcher = publicClassPattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // Fallback: any class definition
        Pattern anyClassPattern = Pattern.compile("class\\s+(\\w+)");
        matcher = anyClassPattern.matcher(code);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "Main";
    }

    private Path findClassFile(Path rootDir, String className) throws IOException {
        try (Stream<Path> walk = Files.walk(rootDir)) {
            return walk.filter(p -> p.getFileName().toString().equals(className + ".class"))
                       .findFirst()
                       .orElse(null);
        }
    }

    @Override
    public CompileResponse compile(CompileRequest request) {
        CompileResponse response = new CompileResponse();
        long startTime = System.currentTimeMillis();

        if (!"java".equalsIgnoreCase(request.getLanguage())) {
            response.setError("Only Java compilation is supported at this time.");
            return response;
        }

        String code = request.getCode();
        String className = extractClassName(code);
        String javaFileName = className + ".java";
        Path tempDir = Paths.get(TEMP_DIR);
        Path javaFilePath = tempDir.resolve(javaFileName);

        try {
            Files.createDirectories(tempDir);
            Files.write(javaFilePath, code.getBytes());

            // Compile with explicit output directory
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
                // Set output location to tempDir
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Arrays.asList(tempDir.toFile()));
                Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjects(javaFilePath.toFile());
                DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                boolean success = compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits).call();

                if (!success) {
                    StringBuilder errorMsg = new StringBuilder("Compilation failed:\n");
                    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                        errorMsg.append(diagnostic.toString()).append("\n");
                    }
                    response.setError(errorMsg.toString());
                    return response;
                }
            }

            // Find the generated .class file (might be in a package subdirectory)
            Path classFilePath = findClassFile(tempDir, className);
            if (classFilePath == null || !Files.exists(classFilePath)) {
                response.setError("Compilation succeeded but .class file not found for class: " + className);
                return response;
            }

            // Determine classpath: parent directory of the .class file
            Path classpathDir = classFilePath.getParent();
            String classpath = classpathDir.toString();

            // Run the compiled class
            ProcessBuilder pb = new ProcessBuilder("java", "-cp", classpath, className);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Drain stdout on its own thread, before waiting. Reading only after
            // waitFor deadlocks as soon as the program prints more than the pipe
            // buffer holds: the child blocks writing, we block waiting, and a
            // program that had finished its work is reported as a timeout.
            StringBuilder output = new StringBuilder();
            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append("\n");
                        }
                    }
                } catch (IOException e) {
                    log.debug("Output stream closed early: {}", e.getMessage());
                }
            }, "local-compile-drain");
            drain.setDaemon(true);
            drain.start();

            // Provide stdin if any
            if (request.getStdin() != null && !request.getStdin().isBlank()) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(request.getStdin().getBytes());
                    os.flush();
                } catch (IOException e) {
                    log.debug("Program did not read its input: {}", e.getMessage());
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                response.setError("Execution timed out (exceeded " + TIMEOUT_SECONDS + " seconds).");
                return response;
            }

            drain.join(2000);
            synchronized (output) {
                response.setOutput(output.toString().trim());
            }

            int exitCode = process.exitValue();
            if (exitCode != 0 && (response.getOutput() == null || response.getOutput().isEmpty())) {
                response.setError("Process exited with code " + exitCode);
            }

        } catch (Exception e) {
            log.error("Compilation/execution error", e);
            response.setError("Internal error: " + e.getMessage());
        } finally {
            // Cleanup
            try {
                Files.deleteIfExists(javaFilePath);
                // Delete the .class file and any package directories
                Path classFile = findClassFile(tempDir, className);
                if (classFile != null) {
                    Files.deleteIfExists(classFile);
                    // Optionally delete empty parent directories
                    Path parent = classFile.getParent();
                    while (parent != null && !parent.equals(tempDir) && parent.toFile().list().length == 0) {
                        Files.deleteIfExists(parent);
                        parent = parent.getParent();
                    }
                }
            } catch (IOException ignored) {}
            response.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        }

        return response;
    }
}