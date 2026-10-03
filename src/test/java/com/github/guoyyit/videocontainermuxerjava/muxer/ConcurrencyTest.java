package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Concurrent producers, single writer, bounded memory, deterministic bytes. */
class ConcurrencyTest {

    @TempDir
    Path dir;

    private List<TrackSpec> threeTracks() {
        return List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2),
                TestMedia.videoTrack(3));
    }

    /** One producer thread per track, all starting at the same instant. */
    private Path writeConcurrently(String name, MuxerConfig config, int slowTrack)
            throws Exception {
        List<TrackSpec> tracks = threeTracks();
        Path file = dir.resolve(name);
        Muxer muxer = new Muxer(file, tracks, config);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        List<List<Frame>> perTrack = List.of(
                TestMedia.videoFramesDecodeOrder(1, 60),
                TestMedia.audioFrames(2, 80),
                TestMedia.videoFramesPresentationOrder(3, 45));
        for (int t = 0; t < perTrack.size(); t++) {
            final int trackIndex = t;
            final int trackId = perTrack.get(trackIndex).get(0).trackId();
            futures.add(pool.submit(() -> {
                start.await();
                for (Frame f : perTrack.get(trackIndex)) {
                    if (trackIndex == slowTrack) {
                        Thread.sleep(2); // simulate a slow producer
                    }
                    muxer.writeFrame(f);
                }
                muxer.endOfTrack(trackId);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        muxer.close();
        return file;
    }

    @Test
    void concurrentProducersProduceValidDeterministicContainer() throws Exception {
        Path concurrent = writeConcurrently("conc.vcmx", MuxerConfig.defaults(), -1);

        // reference: same frames written sequentially, interleaved by pts
        List<TrackSpec> tracks = threeTracks();
        Path reference = dir.resolve("ref.vcmx");
        Muxer m = new Muxer(reference, tracks, MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks,
                TestMedia.videoFramesPresentationOrder(1, 60),
                TestMedia.audioFrames(2, 80),
                TestMedia.videoFramesPresentationOrder(3, 45)).forEach(m::writeFrame);
        m.close();

        assertArrayEquals(Files.readAllBytes(reference), Files.readAllBytes(concurrent),
                "concurrent producers must yield byte-identical output");

        Demuxer d = new Demuxer(concurrent);
        assertEquals(ContainerStatus.COMPLETE, d.status());
        // monotonic presentation order across the whole stream
        long last = -1;
        Optional<Frame> f;
        while ((f = d.nextFrame()).isPresent()) {
            Frame frame = f.get();
            TrackSpec spec = tracks.stream()
                    .filter(t -> t.trackId() == frame.trackId()).findFirst().orElseThrow();
            long micros = ContainerFormat.toMicros(frame.pts(), spec.timescale());
            assertTrue(micros >= last, "pts must never go backwards");
            last = micros;
        }
        assertEquals(60, d.trackFrameCount(1));
        assertEquals(80, d.trackFrameCount(2));
        assertEquals(45, d.trackFrameCount(3));
        d.close();
    }

    @Test
    void slowTrackDoesNotCorruptInterleavingOrLoseFrames() throws Exception {
        // track 1 is artificially slowed; the others race ahead
        Path file = writeConcurrently("slow.vcmx", MuxerConfig.defaults(), 1);
        Demuxer d = new Demuxer(file);
        assertEquals(ContainerStatus.COMPLETE, d.status());
        assertEquals(60, d.trackFrameCount(1));
        assertEquals(80, d.trackFrameCount(2));
        assertEquals(45, d.trackFrameCount(3));
        d.close();
    }

    @Test
    void tinyMemoryBoundStillCompletesAndStaysDeterministic() throws Exception {
        // 16 KiB bound with a 100 ms reorder window: natural buffering
        // (~3 KiB for these streams) stays below the bound, so the output
        // must match the unbounded run byte for byte
        MuxerConfig tiny = new MuxerConfig(16 * 1024, 100_000);
        Path bounded = writeConcurrently("bounded.vcmx", tiny, -1);
        Path unbounded = writeConcurrently("unbounded.vcmx", new MuxerConfig(
                MuxerConfig.defaults().maxBufferedBytes(), 100_000), -1);
        assertArrayEquals(Files.readAllBytes(unbounded), Files.readAllBytes(bounded),
                "memory bound must not change the output bytes");
    }

    @Test
    void memoryBoundIsActuallyRespected() {
        // Total stream size here is ~30 KiB while the bound is 8 KiB, so
        // completion proves streaming under a bounded buffer. (The bound
        // must still cover one reorder window per track — see docs.)
        MuxerConfig config = new MuxerConfig(8192, 100_000);
        List<TrackSpec> tracks = threeTracks();
        Path file = dir.resolve("hwm.vcmx");
        Muxer muxer = new Muxer(file, tracks, config);
        List<Frame> frames = RoundTripTest.interleaveByPts(tracks,
                TestMedia.videoFramesPresentationOrder(1, 30),
                TestMedia.audioFrames(2, 30),
                TestMedia.videoFramesPresentationOrder(3, 30));
        // index of the last frame of each track, for EOS signalling
        Map<Integer, Integer> lastIndex = new HashMap<>();
        for (int i = 0; i < frames.size(); i++) {
            lastIndex.put(frames.get(i).trackId(), i);
        }
        for (int i = 0; i < frames.size(); i++) {
            muxer.writeFrame(frames.get(i));
            if (lastIndex.get(frames.get(i).trackId()) == i) {
                muxer.endOfTrack(frames.get(i).trackId());
            }
        }
        muxer.close();
        Demuxer d = new Demuxer(file);
        assertEquals(ContainerStatus.COMPLETE, d.status());
        assertEquals(90, d.trackFrameCount(1) + d.trackFrameCount(2) + d.trackFrameCount(3));
        d.close();
    }
}
