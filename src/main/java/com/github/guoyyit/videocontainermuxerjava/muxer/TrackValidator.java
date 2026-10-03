package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates track specs and frames before writing or reading. Every rejection
 * carries the track id and field name so the caller can locate the problem.
 */
public final class TrackValidator {

    /** Largest timestamp accepted, keeps pts*1_000_000/timescale overflow-free. */
    static final long MAX_TIMESTAMP = Long.MAX_VALUE / 1_000_000L - 1;

    private TrackValidator() {
    }

    /** Validate a full track table; rejects duplicates and contradictory params. */
    public static void validateTracks(List<TrackSpec> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            throw new ValidationException(-1, "tracks", "at least one track is required");
        }
        Set<Integer> ids = new HashSet<>();
        for (TrackSpec t : tracks) {
            if (t == null) {
                throw new ValidationException(-1, "tracks", "null track spec");
            }
            if (t.trackId() < 0) {
                throw new ValidationException(t.trackId(), "trackId", "must be >= 0");
            }
            if (!ids.add(t.trackId())) {
                throw new ValidationException(t.trackId(), "trackId", "duplicate track id");
            }
            if (t.type() == null) {
                throw new ValidationException(t.trackId(), "type", "must not be null");
            }
            if (t.codec() == null || t.codec().isBlank()) {
                throw new ValidationException(t.trackId(), "codec", "must not be blank");
            }
            if (t.codec().length() > 64) {
                throw new ValidationException(t.trackId(), "codec", "tag too long (max 64 chars)");
            }
            if (t.timescale() <= 0 || t.timescale() > 1_000_000) {
                throw new ValidationException(t.trackId(), "timescale",
                        "must be in (0, 1000000], got " + t.timescale());
            }
            switch (t.type()) {
                case VIDEO -> {
                    if (t.width() <= 0 || t.height() <= 0) {
                        throw new ValidationException(t.trackId(), "width/height",
                                "video track requires positive width and height, got "
                                        + t.width() + "x" + t.height());
                    }
                    if (t.sampleRate() != 0 || t.channels() != 0) {
                        throw new ValidationException(t.trackId(), "sampleRate/channels",
                                "video track must not set audio parameters");
                    }
                }
                case AUDIO -> {
                    if (t.sampleRate() <= 0 || t.channels() <= 0) {
                        throw new ValidationException(t.trackId(), "sampleRate/channels",
                                "audio track requires positive sampleRate and channels, got "
                                        + t.sampleRate() + "Hz/" + t.channels() + "ch");
                    }
                    if (t.width() != 0 || t.height() != 0) {
                        throw new ValidationException(t.trackId(), "width/height",
                                "audio track must not set video parameters");
                    }
                }
            }
        }
    }

    /** Validate one frame against its track spec. */
    public static void validateFrame(TrackSpec spec, Frame frame) {
        int id = spec.trackId();
        if (frame.trackId() != id) {
            throw new ValidationException(id, "frame.trackId",
                    "frame belongs to track " + frame.trackId() + " but was submitted to track " + id);
        }
        checkTimestamp(id, "pts", frame.pts());
        checkTimestamp(id, "dts", frame.dts());
        if (frame.dts() > frame.pts()) {
            throw new ValidationException(id, "dts",
                    "dts " + frame.dts() + " must not exceed pts " + frame.pts());
        }
        if (frame.duration() <= 0) {
            throw new ValidationException(id, "duration",
                    "must be > 0, got " + frame.duration());
        }
        if (frame.duration() > MAX_TIMESTAMP) {
            throw new ValidationException(id, "duration",
                    "exceeds allowed maximum " + MAX_TIMESTAMP);
        }
        if (frame.payload().length == 0) {
            throw new ValidationException(id, "payload", "must not be empty");
        }
    }

    private static void checkTimestamp(int trackId, String field, long value) {
        if (value < 0) {
            throw new ValidationException(trackId, field, "must be >= 0, got " + value);
        }
        if (value > MAX_TIMESTAMP) {
            throw new ValidationException(trackId, field,
                    "exceeds allowed maximum " + MAX_TIMESTAMP);
        }
    }
}
