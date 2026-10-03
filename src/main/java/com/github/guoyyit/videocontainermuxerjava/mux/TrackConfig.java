package com.github.guoyyit.videocontainermuxerjava.mux;

import java.util.Arrays;
import java.util.Objects;

/**
 * Static parameters of a track. Timestamps are expressed in {@link #timescale}
 * ticks per second.
 */
public final class TrackConfig {

    private final Integer trackId;
    private final TrackType type;
    private final String codec;
    private final long timescale;
    private final long reorderWindowTicks;
    private final long presentationReorderTicks;
    private final byte[] codecPrivate;
    private final Integer width;
    private final Integer height;
    private final Integer sampleRate;
    private final Integer channels;
    private final Integer bitsPerSample;

    private TrackConfig(Builder b) {
        this.trackId = b.trackId;
        this.type = Objects.requireNonNull(b.type, "type");
        this.codec = Objects.requireNonNull(b.codec, "codec");
        this.timescale = b.timescale;
        this.reorderWindowTicks = b.reorderWindowTicks;
        this.presentationReorderTicks = b.presentationReorderTicks;
        this.codecPrivate = b.codecPrivate == null ? new byte[0] : b.codecPrivate;
        this.width = b.width;
        this.height = b.height;
        this.sampleRate = b.sampleRate;
        this.channels = b.channels;
        this.bitsPerSample = b.bitsPerSample;
    }

    public Integer trackId() { return trackId; }
    public TrackType type() { return type; }
    public String codec() { return codec; }
    public long timescale() { return timescale; }
    public long reorderWindowTicks() { return reorderWindowTicks; }
    public long presentationReorderTicks() { return presentationReorderTicks; }
    public byte[] codecPrivate() { return codecPrivate.clone(); }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public Integer sampleRate() { return sampleRate; }
    public Integer channels() { return channels; }
    public Integer bitsPerSample() { return bitsPerSample; }

    public static Builder builder(TrackType type) { return new Builder(type); }

    /**
     * Validates the track parameters. Throws {@link ContainerValidationException}
     * with a track id and field name on any contradiction.
     *
     * @param assignedTrackId the id the muxer assigned (used in error messages)
     */
    public void validate(Integer assignedTrackId) {
        int id = assignedTrackId != null ? assignedTrackId : (trackId != null ? trackId : -1);
        if (trackId != null && (trackId < 0 || trackId > 0xFFFF)) {
            throw new ContainerValidationException(id, "trackId",
                    "must be in [0, 65535], got " + trackId);
        }
        if (codec.isEmpty() || codec.length() > ContainerFormat.MAX_CODEC_LEN) {
            throw new ContainerValidationException(id, "codec",
                    "must be 1.." + ContainerFormat.MAX_CODEC_LEN + " characters");
        }
        if (timescale <= 0 || timescale > 1_000_000_000L) {
            throw new ContainerValidationException(id, "timescale",
                    "must be in (0, 1_000_000_000], got " + timescale);
        }
        if (reorderWindowTicks < 0 || reorderWindowTicks > timescale * 60L) {
            throw new ContainerValidationException(id, "reorderWindowTicks",
                    "must be in [0, 60s of ticks], got " + reorderWindowTicks);
        }
        if (presentationReorderTicks < 0 || presentationReorderTicks > timescale * 60L) {
            throw new ContainerValidationException(id, "presentationReorderTicks",
                    "must be in [0, 60s of ticks], got " + presentationReorderTicks);
        }
        if (codecPrivate.length > ContainerFormat.MAX_CODEC_PRIVATE_LEN) {
            throw new ContainerValidationException(id, "codecPrivate",
                    "must be <= " + ContainerFormat.MAX_CODEC_PRIVATE_LEN + " bytes");
        }
        switch (type) {
            case VIDEO -> {
                if (width == null || width <= 0 || width > 1_000_000) {
                    throw new ContainerValidationException(id, "width",
                            "video width must be in (0, 1_000_000], got " + width);
                }
                if (height == null || height <= 0 || height > 1_000_000) {
                    throw new ContainerValidationException(id, "height",
                            "video height must be in (0, 1_000_000], got " + height);
                }
                if (sampleRate != null || channels != null || bitsPerSample != null) {
                    throw new ContainerValidationException(id, "type",
                            "video track must not carry audio parameters");
                }
            }
            case AUDIO -> {
                if (sampleRate == null || sampleRate <= 0 || sampleRate > 192_000) {
                    throw new ContainerValidationException(id, "sampleRate",
                            "audio sampleRate must be in (0, 192_000], got " + sampleRate);
                }
                if (channels == null || channels < 1 || channels > 32) {
                    throw new ContainerValidationException(id, "channels",
                            "audio channels must be in [1, 32], got " + channels);
                }
                if (bitsPerSample == null || bitsPerSample != 8 && bitsPerSample != 16
                        && bitsPerSample != 24 && bitsPerSample != 32) {
                    throw new ContainerValidationException(id, "bitsPerSample",
                            "audio bitsPerSample must be one of 8/16/24/32, got " + bitsPerSample);
                }
                if (width != null || height != null) {
                    throw new ContainerValidationException(id, "type",
                            "audio track must not carry video parameters");
                }
            }
        }
    }

    public static final class Builder {
        private Integer trackId;
        private final TrackType type;
        private String codec;
        private long timescale = 1_000_000L;
        private long reorderWindowTicks;
        private long presentationReorderTicks = -1L; // -1 -> default to 1s of ticks
        private byte[] codecPrivate;
        private Integer width;
        private Integer height;
        private Integer sampleRate;
        private Integer channels;
        private Integer bitsPerSample;

        private Builder(TrackType type) { this.type = Objects.requireNonNull(type); }

        public Builder trackId(int trackId) { this.trackId = trackId; return this; }
        public Builder codec(String codec) { this.codec = codec; return this; }
        public Builder timescale(long timescale) { this.timescale = timescale; return this; }
        public Builder reorderWindowTicks(long ticks) { this.reorderWindowTicks = ticks; return this; }
        public Builder presentationReorderTicks(long ticks) { this.presentationReorderTicks = ticks; return this; }
        public Builder codecPrivate(byte[] data) { this.codecPrivate = data; return this; }
        public Builder width(int width) { this.width = width; return this; }
        public Builder height(int height) { this.height = height; return this; }
        public Builder sampleRate(int sampleRate) { this.sampleRate = sampleRate; return this; }
        public Builder channels(int channels) { this.channels = channels; return this; }
        public Builder bitsPerSample(int bitsPerSample) { this.bitsPerSample = bitsPerSample; return this; }

        public TrackConfig build() {
            if (presentationReorderTicks < 0) {
                presentationReorderTicks = timescale; // 1 second default
            }
            return new TrackConfig(this);
        }
    }

    @Override
    public String toString() {
        return "TrackConfig[id=" + trackId + ", type=" + type + ", codec=" + codec
                + ", timescale=" + timescale + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TrackConfig that)) return false;
        return timescale == that.timescale
                && reorderWindowTicks == that.reorderWindowTicks
                && presentationReorderTicks == that.presentationReorderTicks
                && Objects.equals(trackId, that.trackId)
                && type == that.type
                && Objects.equals(codec, that.codec)
                && Arrays.equals(codecPrivate, that.codecPrivate)
                && Objects.equals(width, that.width)
                && Objects.equals(height, that.height)
                && Objects.equals(sampleRate, that.sampleRate)
                && Objects.equals(channels, that.channels)
                && Objects.equals(bitsPerSample, that.bitsPerSample);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trackId, type, codec, timescale, reorderWindowTicks,
                presentationReorderTicks, width, height, sampleRate, channels, bitsPerSample);
        result = 31 * result + Arrays.hashCode(codecPrivate);
        return result;
    }
}
