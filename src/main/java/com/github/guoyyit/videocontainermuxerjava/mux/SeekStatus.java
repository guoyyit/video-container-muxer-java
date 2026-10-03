package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Outcome of a random-access seek on one track.
 */
public enum SeekStatus {
    /** Resumed from a keyframe at or before the requested timestamp. */
    HIT,
    /** Requested time is before the track's first keyframe. */
    BEFORE_FIRST_KEYFRAME,
    /** The track has no keyframe, so random access is impossible. */
    NO_KEYFRAME,
    /** Requested time is at or beyond the end of the track. */
    BEYOND_END
}
