package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Base class for all container processing failures.
 */
public class ContainerException extends RuntimeException {

    public ContainerException(String message) {
        super(message);
    }

    public ContainerException(String message, Throwable cause) {
        super(message, cause);
    }
}
