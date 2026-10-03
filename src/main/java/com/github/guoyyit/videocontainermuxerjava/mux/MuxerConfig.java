package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Tunables for the muxer. Memory usage of in-flight sample payloads is bounded
 * by {@link #maxPendingBytes} (global, shared across all tracks) plus
 * {@link #maxPendingFramesPerTrack} (per-track fairness cap).
 */
public final class MuxerConfig {

    private final long maxPendingBytes;
    private final int maxPendingFramesPerTrack;
    private final int maxFrameSize;
    private final int maxTracks;

    private MuxerConfig(Builder b) {
        this.maxPendingBytes = b.maxPendingBytes;
        this.maxPendingFramesPerTrack = b.maxPendingFramesPerTrack;
        this.maxFrameSize = b.maxFrameSize;
        this.maxTracks = b.maxTracks;
    }

    public long maxPendingBytes() { return maxPendingBytes; }
    public int maxPendingFramesPerTrack() { return maxPendingFramesPerTrack; }
    public int maxFrameSize() { return maxFrameSize; }
    public int maxTracks() { return maxTracks; }

    public static Builder builder() { return new Builder(); }

    public static MuxerConfig defaults() { return builder().build(); }

    void validate() {
        if (maxPendingBytes < 4096) {
            throw new ContainerValidationException(null, "maxPendingBytes",
                    "must be >= 4096, got " + maxPendingBytes);
        }
        if (maxPendingFramesPerTrack < 2) {
            throw new ContainerValidationException(null, "maxPendingFramesPerTrack",
                    "must be >= 2, got " + maxPendingFramesPerTrack);
        }
        if (maxFrameSize < 1 || maxFrameSize > maxPendingBytes) {
            throw new ContainerValidationException(null, "maxFrameSize",
                    "must be in [1, maxPendingBytes], got " + maxFrameSize);
        }
        if (maxTracks < 1 || maxTracks > ContainerFormat.MAX_TRACKS) {
            throw new ContainerValidationException(null, "maxTracks",
                    "must be in [1, " + ContainerFormat.MAX_TRACKS + "], got " + maxTracks);
        }
    }

    public static final class Builder {
        private long maxPendingBytes = 64L << 20;          // 64 MiB global in-flight payloads
        private int maxPendingFramesPerTrack = 512;
        private int maxFrameSize = 16 << 20;               // 16 MiB per frame
        private int maxTracks = ContainerFormat.MAX_TRACKS;

        public Builder maxPendingBytes(long bytes) { this.maxPendingBytes = bytes; return this; }
        public Builder maxPendingFramesPerTrack(int n) { this.maxPendingFramesPerTrack = n; return this; }
        public Builder maxFrameSize(int bytes) { this.maxFrameSize = bytes; return this; }
        public Builder maxTracks(int n) { this.maxTracks = n; return this; }

        public MuxerConfig build() {
            MuxerConfig c = new MuxerConfig(this);
            c.validate();
            return c;
        }
    }
}
