package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * The container is structurally damaged: bad magic/version, CRC mismatch,
 * truncated frame data or an index that does not match the frame blocks.
 */
public class ContainerCorruptException extends ContainerException {

    public ContainerCorruptException(String message) {
        super(message);
    }

    public ContainerCorruptException(String message, Throwable cause) {
        super(message, cause);
    }
}
