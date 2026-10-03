package com.github.guoyyit.videocontainermuxerjava.muxer;

/**
 * One access unit (encoded video frame or audio sample block).
 *
 * @param trackId  track this frame belongs to
 * @param pts      presentation timestamp in the track's timescale
 * @param dts      decode timestamp in the track's timescale; may differ from
 *                 pts when frames arrive in decode order (e.g. B-frames)
 * @param duration frame duration in the track's timescale
 * @param keyFrame true if decoding may start at this frame
 * @param payload  coded bytes (in-process test media, never null)
 */
public record Frame(int trackId, long pts, long dts, long duration,
                    boolean keyFrame, byte[] payload) {

    public Frame {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
    }
}
