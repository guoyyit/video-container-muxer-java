package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Supported track kinds.
 */
public enum TrackType {
    VIDEO(ContainerFormat.TRACK_VIDEO),
    AUDIO(ContainerFormat.TRACK_AUDIO);

    private final int code;

    TrackType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    static TrackType fromCode(int code) {
        return switch (code) {
            case ContainerFormat.TRACK_VIDEO -> VIDEO;
            case ContainerFormat.TRACK_AUDIO -> AUDIO;
            default -> throw new IllegalArgumentException("unknown track type code: " + code);
        };
    }
}
