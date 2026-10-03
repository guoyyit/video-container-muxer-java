package com.github.guoyyit.videocontainermuxerjava.mux;

import java.util.Arrays;
import java.util.Objects;

/**
 * One media sample (a video frame or an audio packet).
 *
 * @param pts       presentation timestamp in the track's timescale
 * @param dts       decode timestamp in the track's timescale (monotonic per track)
 * @param duration   sample duration in the track's timescale (0 if unknown)
 * @param keyframe  whether the sample is a sync sample (random-access point)
 * @param data      opaque codec payload
 */
public record Frame(long pts, long dts, long duration, boolean keyframe, byte[] data) {

    public Frame {
        Objects.requireNonNull(data, "data");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private long pts;
        private long dts;
        private long duration;
        private boolean keyframe;
        private byte[] data;

        public Builder pts(long pts) { this.pts = pts; return this; }
        public Builder dts(long dts) { this.dts = dts; return this; }
        public Builder duration(long duration) { this.duration = duration; return this; }
        public Builder keyframe(boolean keyframe) { this.keyframe = keyframe; return this; }
        public Builder data(byte[] data) { this.data = data; return this; }

        public Frame build() {
            return new Frame(pts, dts, duration, keyframe, data);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frame frame)) return false;
        return pts == frame.pts && dts == frame.dts && duration == frame.duration
                && keyframe == frame.keyframe && Arrays.equals(data, frame.data);
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(pts);
        result = 31 * result + Long.hashCode(dts);
        result = 31 * result + Long.hashCode(duration);
        result = 31 * result + Boolean.hashCode(keyframe);
        result = 31 * result + Arrays.hashCode(data);
        return result;
    }

    @Override
    public String toString() {
        return "Frame[pts=" + pts + ", dts=" + dts + ", duration=" + duration
                + ", keyframe=" + keyframe + ", data.length=" + data.length + "]";
    }
}
