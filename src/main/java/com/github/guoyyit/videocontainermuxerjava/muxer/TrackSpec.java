package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.util.Map;

/**
 * Immutable description of one track. All fields that influence the coded
 * stream are part of the spec so that a write/read round trip preserves them.
 *
 * @param trackId   unique, non-negative track identifier inside one container
 * @param type      video or audio
 * @param codec     codec tag, e.g. "rawvideo" / "pcm_s16le" (in-process test media)
 * @param timescale ticks per second for pts/dts/duration values of this track
 * @param width     video width in pixels, 0 for audio
 * @param height    video height in pixels, 0 for audio
 * @param sampleRate audio sample rate in Hz, 0 for video
 * @param channels  audio channel count, 0 for video
 * @param metadata  free-form track metadata, preserved across a round trip
 */
public record TrackSpec(
        int trackId,
        TrackType type,
        String codec,
        int timescale,
        int width,
        int height,
        int sampleRate,
        int channels,
        Map<String, String> metadata) {

    public TrackSpec {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
