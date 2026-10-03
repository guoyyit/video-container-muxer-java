package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Rejected track/frame configuration. Carries the offending track id and field
 * name so callers can locate the problem precisely.
 */
public class ContainerValidationException extends ContainerException {

    private final Integer trackId;
    private final String field;
    private final String reason;

    public ContainerValidationException(Integer trackId, String field, String reason) {
        super("validation failed"
                + (trackId != null ? " for track " + trackId : "")
                + (field != null ? " field '" + field + "'" : "")
                + ": " + reason);
        this.trackId = trackId;
        this.field = field;
        this.reason = reason;
    }

    public Integer getTrackId() { return trackId; }
    public String getField() { return field; }
    public String getReason() { return reason; }
}
