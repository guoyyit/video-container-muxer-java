package com.github.guoyyit.videocontainermuxerjava.muxer;

/** Raised when container bytes are malformed, truncated or fail checksums. */
public class CorruptContainerException extends RuntimeException {

    public CorruptContainerException(String message) {
        super(message);
    }
}
