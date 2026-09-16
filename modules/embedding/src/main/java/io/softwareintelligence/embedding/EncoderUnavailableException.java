package io.softwareintelligence.embedding;

/**
 * Thrown when a dense encoder was asked for and cannot be provided — no model directory, missing
 * files, or the optional ONNX runtime absent from the classpath.
 *
 * <p>Separate from a generic failure so callers can tell "you did not configure this" from "this
 * broke", and say so. A missing encoder is a configuration state, not a defect.
 */
public class EncoderUnavailableException extends RuntimeException {
    public EncoderUnavailableException(String message) { super(message); }
    public EncoderUnavailableException(String message, Throwable cause) { super(message, cause); }
}
