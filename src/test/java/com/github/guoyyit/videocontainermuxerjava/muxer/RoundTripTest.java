package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Round-trip: tracks, codec params, metadata and frames survive write+read. */
class RoundTripTest {

    @TempDir
    Path dir;

    /** Merge frame lists into one arrival sequence ordered by pts in micros. */
    static List<Frame> interleaveByPts(List<TrackSpec> tracks, List<Frame>... groups) {
        List<Frame> all = new ArrayList<>();
        for (List<Frame> g : groups) {
            all.addAll(g);
        }
        all.sort(Comparator.comparingLong(f -> ptsMicros(tracks, f)));
        return all;
    }

    static long ptsMicros(List<TrackSpec> tracks, Frame f) {
        for (TrackSpec t : tracks) {
            if (t.trackId() == f.trackId()) {
                return ContainerFormat.toMicros(f.pts(), t.timescale());
            }
        }
        throw new IllegalArgumentException("unknown track " + f.trackId());
    }

    private List<Frame> writeAndReadBack(Path file, List<TrackSpec> tracks,
                                         List<Frame> frames) {
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        for (Frame f : frames) {
            muxer.writeFrame(f);
        }
        muxer.close();

        Demuxer demuxer = new Demuxer(file);
        assertEquals(ContainerStatus.COMPLETE, demuxer.status());
        assertEquals(tracks.size(), demuxer.tracks().size());
        for (TrackSpec t : tracks) {
            TrackSpec read = demuxer.tracks().stream()
                    .filter(x -> x.trackId() == t.trackId()).findFirst().orElseThrow();
            assertEquals(t, read, "track spec must survive the round trip");
        }
        List<Frame> out = new ArrayList<>();
        Optional<Frame> f;
        while ((f = demuxer.nextFrame()).isPresent()) {
            out.add(f.get());
        }
        demuxer.close();
        return out;
    }

    @Test
    void framesAndTracksSurviveRoundTrip() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        List<Frame> video = TestMedia.videoFramesPresentationOrder(1, 30);
        List<Frame> audio = TestMedia.audioFrames(2, 20);
        List<Frame> all = interleaveByPts(tracks, video, audio);

        List<Frame> back = writeAndReadBack(dir.resolve("a.vcmx"), tracks, all);
        assertEquals(all.size(), back.size());
        // output order is deterministic: sorted by (pts micros, trackId)
        long lastPtsMicros = -1;
        for (Frame f : back) {
            long micros = ptsMicros(tracks, f);
            assertTrue(micros >= lastPtsMicros, "presentation time must never go backwards");
            lastPtsMicros = micros;
        }
        // every input frame is present with identical fields
        for (Frame in : all) {
            Frame match = back.stream()
                    .filter(f -> f.trackId() == in.trackId() && f.pts() == in.pts())
                    .findFirst().orElseThrow();
            assertEquals(in.dts(), match.dts());
            assertEquals(in.duration(), match.duration());
            assertEquals(in.keyFrame(), match.keyFrame());
            assertArrayEquals(in.payload(), match.payload());
        }
    }

    @Test
    void sameInputProducesByteIdenticalOutputRegardlessOfArrivalOrder() throws Exception {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        List<Frame> video = TestMedia.videoFramesPresentationOrder(1, 40);
        List<Frame> audio = TestMedia.audioFrames(2, 30);

        // run 1: presentation order, tracks interleaved by pts
        Path f1 = dir.resolve("r1.vcmx");
        Muxer m1 = new Muxer(f1, tracks, MuxerConfig.defaults());
        interleaveByPts(tracks, video, audio).forEach(m1::writeFrame);
        m1.close();

        // run 2: video in decode order, audio in order, merged by decode/pts time
        Path f2 = dir.resolve("r2.vcmx");
        List<Frame> decodeOrder = new ArrayList<>();
        decodeOrder.addAll(TestMedia.videoFramesDecodeOrder(1, 40));
        decodeOrder.addAll(audio);
        decodeOrder.sort(Comparator.comparingLong(f -> ptsMicros(tracks,
                new Frame(f.trackId(), f.dts(), f.dts(), f.duration(), f.keyFrame(), f.payload()))));
        Muxer m2 = new Muxer(f2, tracks, MuxerConfig.defaults());
        decodeOrder.forEach(m2::writeFrame);
        m2.close();

        // run 3: presentation order shuffled inside small sliding windows
        Path f3 = dir.resolve("r3.vcmx");
        List<Frame> windowed = interleaveByPts(tracks, video, audio);
        Random rng = new Random(42);
        List<Frame> shuffled = new ArrayList<>();
        for (int i = 0; i < windowed.size(); i += 8) {
            List<Frame> window = new ArrayList<>(windowed.subList(i, Math.min(i + 8, windowed.size())));
            java.util.Collections.shuffle(window, rng);
            shuffled.addAll(window);
        }
        Muxer m3 = new Muxer(f3, tracks, MuxerConfig.defaults());
        shuffled.forEach(m3::writeFrame);
        m3.close();

        assertArrayEquals(Files.readAllBytes(f1), Files.readAllBytes(f2),
                "decode-order arrival must not change the output bytes");
        assertArrayEquals(Files.readAllBytes(f1), Files.readAllBytes(f3),
                "shuffled arrival must not change the output bytes");
    }
}
