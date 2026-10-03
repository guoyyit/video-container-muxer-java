package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic in-process test media. No external files or services:
 * payloads are pseudo-random bytes derived from (trackId, presentation index)
 * with a fixed seed, so every run produces the same frames.
 */
final class TestMedia {

    static final int VIDEO_TIMESCALE = 90_000;
    static final int VIDEO_FPS = 25;
    static final long FRAME_DURATION = VIDEO_TIMESCALE / VIDEO_FPS; // 3600 ticks
    static final int GOP = 12;
    /** pts shift so that dts (decode-order based) never exceeds pts. */
    static final int B_FRAME_DELAY = 2;

    static final int AUDIO_TIMESCALE = 48_000;
    static final long AUDIO_BLOCK = 1024; // samples per frame

    private TestMedia() {
    }

    static TrackSpec videoTrack(int id) {
        return new TrackSpec(id, TrackType.VIDEO, "rawvideo", VIDEO_TIMESCALE,
                320, 240, 0, 0, Map.of("language", "und", "encoder", "testgen"));
    }

    static TrackSpec audioTrack(int id) {
        return new TrackSpec(id, TrackType.AUDIO, "pcm_s16le", AUDIO_TIMESCALE,
                0, 0, 48_000, 2, Map.of("language", "eng"));
    }

    /** Deterministic payload bytes for one access unit. */
    static byte[] payload(int trackId, long index, int size) {
        byte[] out = new byte[size];
        long state = (trackId * 1_000_003L) ^ (index * 2_654_435_761L) ^ 0x9E3779B9L;
        for (int i = 0; i < size; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (state >>> 33);
        }
        // embed a human-readable tag so corruption is easy to spot in dumps
        byte[] tag = ("t" + trackId + "#" + index).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, out, 0, Math.min(tag.length, size));
        return out;
    }

    /**
     * Video frames in presentation order. Frame n has pts=(n+DELAY)*dur and a
     * dts derived from its decode-order position; keyframes every GOP frames.
     */
    static List<Frame> videoFramesPresentationOrder(int trackId, int count) {
        List<Frame> frames = new ArrayList<>(count);
        List<Integer> decodeOrder = decodeOrder(count);
        for (int k = 0; k < decodeOrder.size(); k++) {
            int n = decodeOrder.get(k);
            long pts = (n + B_FRAME_DELAY) * FRAME_DURATION;
            long dts = k * FRAME_DURATION;
            boolean key = n % GOP == 0;
            frames.add(new Frame(trackId, pts, dts, FRAME_DURATION, key,
                    payload(trackId, n, key ? 900 : 300)));
        }
        // sort back into presentation order
        frames.sort((a, b) -> Long.compare(a.pts(), b.pts()));
        return frames;
    }

    /** Same frames but in decode (arrival) order: I, P, B, B, P, B, B, ... */
    static List<Frame> videoFramesDecodeOrder(int trackId, int count) {
        List<Frame> byPts = videoFramesPresentationOrder(trackId, count);
        List<Frame> out = new ArrayList<>(byPts.size());
        List<Integer> decodeOrder = decodeOrder(count);
        for (int k = 0; k < decodeOrder.size(); k++) {
            int n = decodeOrder.get(k);
            long pts = (n + B_FRAME_DELAY) * FRAME_DURATION;
            for (Frame f : byPts) {
                if (f.pts() == pts) {
                    out.add(f);
                    break;
                }
            }
        }
        return out;
    }

    /** Decode order of presentation indices: 0, 3, 1, 2, 6, 4, 5, 9, 7, 8, ... */
    static List<Integer> decodeOrder(int count) {
        List<Integer> order = new ArrayList<>(count);
        if (count == 0) {
            return order;
        }
        order.add(0);
        int nextP = 3;
        while (nextP < count) {
            order.add(nextP);
            for (int b = nextP - 2; b < nextP && b < count; b++) {
                order.add(b);
            }
            nextP += 3;
        }
        for (int b = nextP - 2; b < count; b++) {
            if (!order.contains(b)) {
                order.add(b);
            }
        }
        return order;
    }

    /** Audio frames in (the only) order; every audio frame is a keyframe. */
    static List<Frame> audioFrames(int trackId, int count) {
        List<Frame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(new Frame(trackId, i * AUDIO_BLOCK, i * AUDIO_BLOCK, AUDIO_BLOCK,
                    true, payload(trackId, i, 256)));
        }
        return frames;
    }
}
