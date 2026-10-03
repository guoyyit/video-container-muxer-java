package com.github.guoyyit.videocontainermuxerjava.muxer;

/** Health of an opened container, determined deterministically on open. */
public enum ContainerStatus {
    /** Footer present and valid; every chunk checksum verified. */
    COMPLETE,
    /** Footer missing or trailer flag unset: writer was cancelled/interrupted. */
    INCOMPLETE,
    /** Footer present but a chunk checksum or length is wrong. */
    CORRUPT
}
