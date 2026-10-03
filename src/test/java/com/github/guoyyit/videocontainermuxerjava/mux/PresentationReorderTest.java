package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Presentation ordering: PTS may differ from DTS (B-frames) and frames may
 * arrive out of order; the reader must present in PTS order without backward
 * flow, and the muxer must produce byte-identical output regardless of arrival
 * order.
 */
class PresentationReorderTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    /**
     * B-frame pattern in decode order: I0, P3, B1, B2, P6, B4, B5, ...
     * DTS = decode position, PTS = presentation position. The I/P reference
     * frame is decoded before the B-frames but presented after them.
     */
    static List<Frame> bFramePattern(int groups, long frameDuration) {
        List<Frame> frames = new ArrayList<>();
        int decode = 0;
        // I-frame: decoded and presented first
        frames.add(Frame.builder()
                .pts(0L).dts(decode++ * frameDuration)
                .duration(frameDuration).keyframe(true)
                .data("I0".getBytes(java.nio.charset.StandardCharsets.UTF_8)).build());
        for (int g = 0; g < groups; g++) {
            long base = g * 3L;
            // P-frame decoded first, presented at base+3
            frames.add(Frame.builder()
                    .pts((base + 3) * frameDuration)
                    .dts(decode++ * frameDuration)
                    .duration(frameDuration).keyframe(false)
                    .data(("P" + (base + 3)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
            // B-frames decoded after, presented at base+1 / base+2
            frames.add(Frame.builder()
                    .pts((base + 1) * frameDuration)
                    .dts(decode++ * frameDuration)
                    .duration(frameDuration).keyframe(false)
                    .data(("B" + (base + 1)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
            frames.add(Frame.builder()
                    .pts((base + 2) * frameDuration)
                    .dts(decode++ * frameDuration)
                    .duration(frameDuration).keyframe(false)
                    .data(("B" + (base + 2)).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        return frames;
    }

    static TrackConfig videoConfig(long reorderWindow) {
        return TrackConfig.builder(TrackType.VIDEO)
                .codec("AVC").timescale(US)
                .width(640).height(480)
                .reorderWindowTicks(reorderWindow)
                .build();
    }

    static void mux(Path path, List<Frame> frames, long reorderWindow) throws Exception {
        try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
            int v = muxer.addTrack(videoConfig(reorderWindow));
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                for (Frame f : frames) vo.write(f);
            }
        }
    }

    @Test
    void bFramesPresentInPtsOrder() throws Exception {
        Path path = tempDir.resolve("bframes.mux");
        long dur = 40000;
        List<Frame> decodeOrder = bFramePattern(4, dur); // 12 frames
        mux(path, decodeOrder, 0);

        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(13, reader.track(0).frameCount());
            Iterator<Frame> it = reader.frames(0);
            long prevPts = Long.MIN_VALUE;
            long expectedPts = 0;
            int count = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                // presentation order: PTS strictly increasing, intervals equal to input
                assertEquals(expectedPts, f.pts(), "PTS must be sequential in presentation");
                assertTrue(f.pts() > prevPts, "presentation time must not flow backward");
                if (prevPts != Long.MIN_VALUE) {
                    assertEquals(dur, f.pts() - prevPts, "presentation interval must match input");
                }
                prevPts = f.pts();
                expectedPts += dur;
                count++;
            }
            assertEquals(13, count);
        }
    }

    @Test
    void outOfOrderArrivalWithReorderWindowIsByteIdentical() throws Exception {
        long dur = 40000;
        List<Frame> decodeOrder = bFramePattern(4, dur);

        // shuffled arrival: swap adjacent frames within a 2-frame window
        List<Frame> shuffled = new ArrayList<>(decodeOrder);
        for (int i = 0; i + 1 < shuffled.size(); i += 2) {
            java.util.Collections.swap(shuffled, i, i + 1);
        }

        Path p1 = tempDir.resolve("ordered.mux");
        Path p2 = tempDir.resolve("shuffled.mux");
        mux(p1, decodeOrder, 2 * dur);
        mux(p2, shuffled, 2 * dur);

        assertArrayEquals(Files.readAllBytes(p1), Files.readAllBytes(p2),
                "output must be byte-identical regardless of arrival order");

        // and the shuffled file still presents correctly
        try (ContainerReader reader = ContainerReader.open(p2)) {
            Iterator<Frame> it = reader.frames(0);
            long prevPts = Long.MIN_VALUE;
            int count = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                assertTrue(f.pts() > prevPts);
                prevPts = f.pts();
                count++;
            }
            assertEquals(13, count);
        }
    }

    @Test
    void durationReflectsActualContent() throws Exception {
        Path path = tempDir.resolve("dur.mux");
        long dur = 40000;
        List<Frame> frames = bFramePattern(3, dur); // 9 frames, last PTS = 8*dur
        mux(path, frames, 0);
        try (ContainerReader reader = ContainerReader.open(path)) {
            TrackInfo t = reader.track(0);
            // duration = max(pts + duration) = 9*dur + dur = 10*dur
            assertEquals(10 * dur, t.durationTicks());
            assertEquals((10 * dur * US) / US, t.durationUs());
        }
    }
}
