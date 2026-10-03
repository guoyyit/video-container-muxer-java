package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * Read-side description of one track.
 */
public final class TrackInfo {

    private final int trackId;
    private final TrackType type;
    private final String codec;
    private final long timescale;
    private final long durationTicks;
    private final long frameCount;
    private final long keyframeCount;
    private final long reorderWindowTicks;
    private final long presentationReorderTicks;
    private final byte[] codecPrivate;
    private final Integer width;
    private final Integer height;
    private final Integer sampleRate;
    private final Integer channels;
    private final Integer bitsPerSample;

    TrackInfo(Builder b) {
        this.trackId = b.trackId;
        this.type = b.type;
        this.codec = b.codec;
        this.timescale = b.timescale;
        this.durationTicks = b.durationTicks;
        this.frameCount = b.frameCount;
        this.keyframeCount = b.keyframeCount;
        this.reorderWindowTicks = b.reorderWindowTicks;
        this.presentationReorderTicks = b.presentationReorderTicks;
        this.codecPrivate = b.codecPrivate;
        this.width = b.width;
        this.height = b.height;
        this.sampleRate = b.sampleRate;
        this.channels = b.channels;
        this.bitsPerSample = b.bitsPerSample;
    }

    public int trackId() { return trackId; }
    public TrackType type() { return type; }
    public String codec() { return codec; }
    public long timescale() { return timescale; }
    public long durationTicks() { return durationTicks; }
    public long frameCount() { return frameCount; }
    public long keyframeCount() { return keyframeCount; }
    public long reorderWindowTicks() { return reorderWindowTicks; }
    public long presentationReorderTicks() { return presentationReorderTicks; }
    public byte[] codecPrivate() { return codecPrivate.clone(); }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public Integer sampleRate() { return sampleRate; }
    public Integer channels() { return channels; }
    public Integer bitsPerSample() { return bitsPerSample; }

    /** Duration in microseconds, rounded down. */
    public long durationUs() {
        return timescale > 0 ? (durationTicks * 1_000_000L) / timescale : 0L;
    }

    static Builder builder() { return new Builder(); }

    static final class Builder {
        private int trackId;
        private TrackType type;
        private String codec;
        private long timescale;
        private long durationTicks;
        private long frameCount;
        private long keyframeCount;
        private long reorderWindowTicks;
        private long presentationReorderTicks;
        private byte[] codecPrivate = new byte[0];
        private Integer width;
        private Integer height;
        private Integer sampleRate;
        private Integer channels;
        private Integer bitsPerSample;

        Builder trackId(int v) { this.trackId = v; return this; }
        Builder type(TrackType v) { this.type = v; return this; }
        Builder codec(String v) { this.codec = v; return this; }
        Builder timescale(long v) { this.timescale = v; return this; }
        Builder durationTicks(long v) { this.durationTicks = v; return this; }
        Builder frameCount(long v) { this.frameCount = v; return this; }
        Builder keyframeCount(long v) { this.keyframeCount = v; return this; }
        Builder reorderWindowTicks(long v) { this.reorderWindowTicks = v; return this; }
        Builder presentationReorderTicks(long v) { this.presentationReorderTicks = v; return this; }
        Builder codecPrivate(byte[] v) { this.codecPrivate = v; return this; }
        Builder width(Integer v) { this.width = v; return this; }
        Builder height(Integer v) { this.height = v; return this; }
        Builder sampleRate(Integer v) { this.sampleRate = v; return this; }
        Builder channels(Integer v) { this.channels = v; return this; }
        Builder bitsPerSample(Integer v) { this.bitsPerSample = v; return this; }

        TrackInfo build() { return new TrackInfo(this); }
    }
}
