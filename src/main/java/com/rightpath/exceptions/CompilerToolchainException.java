package com.rightpath.exceptions;

/**
 * The platform could not run the language at all — {@code javac}, {@code python}
 * or {@code gcc} is missing from the server image, or the working directory could
 * not be created.
 *
 * <p>Distinct from {@link CompilerException} because this is never the
 * candidate's fault: it is an operational failure that should page someone, and
 * the candidate should be told to retry rather than shown a "your code is
 * broken" message about code that is fine.</p>
 */
public class CompilerToolchainException extends CompilerException {
    private static final long serialVersionUID = 1L;

    public CompilerToolchainException(String message) {
        super(message);
    }

    public CompilerToolchainException(String message, Throwable cause) {
        super(message, cause);
    }
}
