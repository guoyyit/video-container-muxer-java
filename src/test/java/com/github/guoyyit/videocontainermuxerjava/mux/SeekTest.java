package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Keyframe index and random-access semantics: seek snaps to the keyframe at or
 * before the requested presentation time, and every distinct outcome
 * (hit / before-first / no-keyframe / beyond-end) is distinguishable.
 */
class SeekTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    static List<Frame> videoWithKeyframes(int count, long frameDuration, int keyframeInterval) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long pts = i * frameDuration;
            frames.add(Frame.builder()
                    .pts(pts).dts(pts).duration(frameDuration)
                    .keyframe(i % keyframeInterval == 0)
                    .data(("f" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        return frames;
    }

    static List<Frame> videoNoKeyframes(int count, long frameDuration) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long pts = i * frameDuration;
            frames.add(Frame.builder()
                    .pts(pts).dts(pts).duration(frameDuration)
                    .keyframe(false)
                    .data(("f" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        return frames;
    }

    static void mux(Path path, List<Frame> frames) throws Exception {
        try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(640).height(480).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                for (Frame f : frames) vo.write(f);
            }
        }
    }

    @Test
    void seekSnapsToKeyframeAtOrBefore() throws Exception {
        Path path = tempDir.resolve("seek.mux");
        long dur = 40000;
        // keyframes at pts 0, 400000, 800000, ... (every 10 frames)
        mux(path, videoWithKeyframes(35, dur, 10));

        try (ContainerReader reader = ContainerReader.open(path)) {
            // exact keyframe hit
            SeekOutcome o = reader.seek(0, 0);
            assertEquals(SeekStatus.HIT, o.status());
            assertEquals(0, o.resumedPts());
            assertEquals(0, o.requestedPts());

            // time between keyframes snaps to the earlier keyframe
            o = reader.seek(0, 500000); // between 400000 and 800000
            assertEquals(SeekStatus.HIT, o.status());
            assertEquals(400000, o.resumedPts());
            assertEquals(500000, o.requestedPts());

            o = reader.seek(0, 799999);
            assertEquals(400000, o.resumedPts());

            o = reader.seek(0, 800000);
            assertEquals(800000, o.resumedPts());

            // resume from seek point: first frame is the keyframe, then PTS order
            Iterator<Frame> it = reader.framesFrom(0, 500000);
            assertTrue(it.hasNext());
            Frame first = it.next();
            assertEquals(400000, first.pts());
            assertTrue(first.keyframe());
            long prev = first.pts();
            int count = 1;
            while (it.hasNext()) {
                Frame f = it.next();
                assertTrue(f.pts() > prev);
                prev = f.pts();
                count++;
            }
            // frames from pts 400000 to 34*40000=1360000 -> 35-10 = 25 frames
            assertEquals(25, count);
        }
    }

    @Test
    void beforeFirstKeyframeIsDistinguishable() throws Exception {
        Path path = tempDir.resolve("seek.mux");
        long dur = 40000;
        // first keyframe at pts 400000 (frame 10), not at 0
        List<Frame> frames = videoWithKeyframes(20, dur, 10);
        // make frame 0..9 non-keyframes, frame 10 keyframe
        for (int i = 0; i < 10; i++) {
            Frame f = frames.get(i);
            frames.set(i, Frame.builder().pts(f.pts()).dts(f.dts()).duration(f.duration())
                    .keyframe(false).data(f.data()).build());
        }
        mux(path, frames);

        try (ContainerReader reader = ContainerReader.open(path)) {
            SeekOutcome o = reader.seek(0, 0);
            assertEquals(SeekStatus.BEFORE_FIRST_KEYFRAME, o.status());
            assertEquals(-1, o.resumedPts());

            o = reader.seek(0, 399999);
            assertEquals(SeekStatus.BEFORE_FIRST_KEYFRAME, o.status());

            o = reader.seek(0, 400000);
            assertEquals(SeekStatus.HIT, o.status());
            assertEquals(400000, o.resumedPts());
        }
    }

    @Test
    void noKeyframesIsDistinguishable() throws Exception {
        Path path = tempDir.resolve("nokf.mux");
        mux(path, videoNoKeyframes(20, 40000));

        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(0, reader.track(0).keyframeCount());
            SeekOutcome o = reader.seek(0, 0);
            assertEquals(SeekStatus.NO_KEYFRAME, o.status());
            o = reader.seek(0, 500000);
            assertEquals(SeekStatus.NO_KEYFRAME, o.status());

            // frames still readable sequentially
            Iterator<Frame> it = reader.frames(0);
            int count = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                assertEquals(count * 40000L, f.pts());
                count++;
            }
            assertEquals(20, count);
        }
    }

    @Test
    void beyondEndIsDistinguishable() throws Exception {
        Path path = tempDir.resolve("end.mux");
        long dur = 40000;
        mux(path, videoWithKeyframes(20, dur, 5)); // 20 frames, duration = 20*dur

        try (ContainerReader reader = ContainerReader.open(path)) {
            long duration = reader.track(0).durationTicks();
            assertEquals(20 * dur, duration);

            SeekOutcome o = reader.seek(0, duration);
            assertEquals(SeekStatus.BEYOND_END, o.status());

            o = reader.seek(0, duration + 1);
            assertEquals(SeekStatus.BEYOND_END, o.status());

            o = reader.seek(0, duration - 1);
            assertEquals(SeekStatus.HIT, o.status());
            assertEquals(15 * dur, o.resumedPts()); // keyframe at 15*dur
        }
    }

    @Test
    void seekIsDeterministic() throws Exception {
        Path path = tempDir.resolve("det.mux");
        mux(path, videoWithKeyframes(30, 40000, 7));

        try (ContainerReader reader = ContainerReader.open(path)) {
            for (long pts = 0; pts < 30 * 40000; pts += 12345) {
                SeekOutcome o1 = reader.seek(0, pts);
                SeekOutcome o2 = reader.seek(0, pts);
                assertEquals(o1, o2, "seek must be deterministic for pts " + pts);
            }
        }
    }
}
