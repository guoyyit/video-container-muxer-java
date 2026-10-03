package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * The file is a partially written container (interrupted/cancelled mux): the
 * trailer is missing or the completeness flag is not set.
 */
public class ContainerIncompleteException extends ContainerException {

    public ContainerIncompleteException(String path) {
        super("container is incomplete (write interrupted): " + path);
    }
}
