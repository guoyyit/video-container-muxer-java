package com.github.guoyyit.videocontainermuxerjava.muxer;

/**
 * Thrown when track specs or timestamps fail validation. The message always
 * names the offending track and field so callers can locate the problem.
 */
public class ValidationException extends RuntimeException {

    private final int trackId;
    private final String field;

    public ValidationException(int trackId, String field, String message) {
        super("track=" + trackId + " field=" + field + ": " + message);
        this.trackId = trackId;
        this.field = field;
    }

    public int trackId() {
        return trackId;
    }

    public String field() {
        return field;
    }
}
