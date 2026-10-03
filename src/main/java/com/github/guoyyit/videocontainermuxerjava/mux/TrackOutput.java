package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * A single producer's handle to one track. {@link #close()} signals
 * end-of-track to the muxer.
 */
public final class TrackOutput implements AutoCloseable {

    private final ContainerMuxer muxer;
    private final int trackId;

    TrackOutput(ContainerMuxer muxer, int trackId) {
        this.muxer = muxer;
        this.trackId = trackId;
    }

    public int trackId() { return trackId; }

    public void write(Frame frame) throws InterruptedException {
        muxer.writeFrame(trackId, frame);
    }

    @Override
    public void close() {
        muxer.endTrack(trackId);
    }
}
