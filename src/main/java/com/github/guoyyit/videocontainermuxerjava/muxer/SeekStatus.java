package com.github.guoyyit.videocontainermuxerjava.muxer;

/** Outcome classification of a seek request. */
public enum SeekStatus {
    /** Requested time matches a frame pts exactly. */
    EXACT,
    /** Requested time falls in a gap between frames; positioned at the
     *  keyframe at or before the requested time. */
    IN_GAP,
    /** Requested time is before the first keyframe of the track. */
    BEFORE_FIRST_KEYFRAME,
    /** Track has no keyframes at all, so random access is impossible. */
    NO_KEYFRAMES,
    /** Requested time is negative or beyond the track duration. */
    OUT_OF_RANGE
}
