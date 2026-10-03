package com.github.guoyyit.videocontainermuxerjava.muxer;

/**
 * Tuning knobs for {@link Muxer}.
 *
 * @param maxBufferedBytes upper bound of bytes buffered in memory before
 *                         back-pressure is applied to producers; bounds memory
 *                         regardless of track count or stream length
 * @param reorderWindowPts maximum pts distance (per track timescale is
 *                         normalised to microseconds) a frame may arrive late;
 *                         frames later than this are rejected
 */
public record MuxerConfig(long maxBufferedBytes, long reorderWindowPts) {

    public static MuxerConfig defaults() {
        return new MuxerConfig(8L * 1024 * 1024, 1_000_000L);
    }
}
