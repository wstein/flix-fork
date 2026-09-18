package dev.flix.runtime;

/**
 * Something went wrong evaluating an expression inside a paused program.
 *
 * <p>Its own type, and not a {@link RuntimeException}, so a debugger can tell "this machinery
 * failed" from "the expression threw" — the second is a result a user wants to see and the first is
 * a defect. The distinction survives the debug connection, where all that arrives is a class name
 * and a message.
 */
public final class DebugEvalException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DebugEvalException(String message) {
        super(message);
    }

    public DebugEvalException(String message, Throwable cause) {
        super(message, cause);
    }
}
