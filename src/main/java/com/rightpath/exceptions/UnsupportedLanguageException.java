package com.rightpath.exceptions;

/**
 * The submission named a language this platform does not run.
 *
 * <p>A client-side mistake rather than a compilation failure, so it answers 400
 * with the list of languages that would have worked instead of 422.</p>
 */
public class UnsupportedLanguageException extends CompilerException {
    private static final long serialVersionUID = 1L;

    public UnsupportedLanguageException(String message) {
        super(message);
    }
}
