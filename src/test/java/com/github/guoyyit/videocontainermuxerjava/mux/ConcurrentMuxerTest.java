package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multiple producers feed distinct tracks concurrently; a single writer thread
 * interleaves deterministically. A slow/blocking producer must not misalign
 * other tracks or grow memory without bound.
 */
class ConcurrentMuxerTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    static List<Frame> frames(int count, long startPts, long dur, int keyframeInterval,
                              String prefix) {
        List<Frame> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Frame.builder()
                    .pts(startPts + i * dur).dts(startPts + i * dur).duration(dur)
                    .keyframe(i % keyframeInterval == 0)
                    .data((prefix + "-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        return list;
    }

    @Test
    void concurrentProducersAllFramesPresent() throws Exception {
        Path path = tempDir.resolve("concurrent.mux");
        int trackCount = 4;
        int framesPerTrack = 60;

        int[] trackIds;
        try (ContainerMuxer muxer = ContainerMuxer.create(path,
                MuxerConfig.builder().maxPendingBytes(1 << 20).maxFrameSize(64 << 10).build())) {
            trackIds = new int[trackCount];
            for (int t = 0; t < trackCount; t++) {
                final int ti = t;
                trackIds[t] = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                        .codec("AVC").timescale(US).width(320).height(240)
                        .build());
            }
            muxer.start();

            ExecutorService pool = Executors.newFixedThreadPool(trackCount);
            CountDownLatch done = new CountDownLatch(trackCount);
            AtomicInteger errors = new AtomicInteger();
            for (int t = 0; t < trackCount; t++) {
                final int ti = t;
                final int trackId = trackIds[t];
                pool.submit(() -> {
                    try (TrackOutput out = muxer.output(trackId)) {
                        List<Frame> frames = frames(framesPerTrack, ti * 100000L, 40000, 10,
                                "t" + ti);
                        for (Frame f : frames) {
                            out.write(f);
                            if (ti == 0) {
                                Thread.sleep(2); // one slow producer
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS), "producers must finish");
            pool.shutdown();
            assertTrue(errors.get() == 0, "no producer errors");
        }

        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(trackCount, reader.tracks().size());
            for (int t = 0; t < trackCount; t++) {
                TrackInfo info = reader.track(trackIds[t]);
                assertEquals(framesPerTrack, info.frameCount(),
                        "track " + t + " frame count");
                // verify every frame present and in order
                Iterator<Frame> it = reader.frames(trackIds[t]);
                long prev = Long.MIN_VALUE;
                int count = 0;
                while (it.hasNext()) {
                    Frame f = it.next();
                    assertTrue(f.pts() > prev, "track " + t + " pts order");
                    prev = f.pts();
                    count++;
                }
                assertEquals(framesPerTrack, count);
            }
        }
    }

    @Test
    void outputIsDeterministicAcrossRunsWithDifferentTiming() throws Exception {
        int trackCount = 3;
        int framesPerTrack = 30;
        List<Path> outputs = new ArrayList<>();

        for (int run = 0; run < 3; run++) {
            Path path = tempDir.resolve("det-" + run + ".mux");
            try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
                int[] trackIds = new int[trackCount];
                for (int t = 0; t < trackCount; t++) {
                    trackIds[t] = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                            .codec("AVC").timescale(US).width(320).height(240).build());
                }
                muxer.start();
                ExecutorService pool = Executors.newFixedThreadPool(trackCount);
                CountDownLatch done = new CountDownLatch(trackCount);
                for (int t = 0; t < trackCount; t++) {
                    final int ti = t;
                    final int trackId = trackIds[t];
                    final int delay = run * 3 + ti; // different timing each run
                    pool.submit(() -> {
                        try (TrackOutput out = muxer.output(trackId)) {
                            List<Frame> frames = frames(framesPerTrack, ti * 50000L, 40000, 5,
                                    "t" + ti);
                            for (Frame f : frames) {
                                out.write(f);
                                if (delay > 0) Thread.sleep(delay);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertTrue(done.await(30, TimeUnit.SECONDS));
                pool.shutdown();
            }
            outputs.add(path);
        }

        byte[] first = Files.readAllBytes(outputs.get(0));
        for (int i = 1; i < outputs.size(); i++) {
            assertArrayEquals(first, Files.readAllBytes(outputs.get(i)),
                    "output must be byte-identical regardless of producer timing");
        }
    }

    @Test
    void boundedMemoryDoesNotGrowWithTrackCount() throws Exception {
        // many tracks with small per-track budget; producers should block, not OOM
        int trackCount = 16;
        int framesPerTrack = 100;
        Path path = tempDir.resolve("bounded.mux");
        int[] trackIds;
        try (ContainerMuxer muxer = ContainerMuxer.create(path,
                MuxerConfig.builder()
                        .maxPendingBytes(64 << 10) // 64 KiB global
                        .maxPendingFramesPerTrack(4)
                        .maxFrameSize(4 << 10)
                        .build())) {
            trackIds = new int[trackCount];
            for (int t = 0; t < trackCount; t++) {
                trackIds[t] = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                        .codec("AVC").timescale(US).width(160).height(120).build());
            }
            muxer.start();
            ExecutorService pool = Executors.newFixedThreadPool(trackCount);
            CountDownLatch done = new CountDownLatch(trackCount);
            for (int t = 0; t < trackCount; t++) {
                final int ti = t;
                final int trackId = trackIds[t];
                pool.submit(() -> {
                    try (TrackOutput out = muxer.output(trackId)) {
                        for (int i = 0; i < framesPerTrack; i++) {
                            byte[] data = new byte[256]; // small frames
                            out.write(Frame.builder()
                                    .pts(i * 40000L + ti * 1000000L)
                                    .dts(i * 40000L + ti * 1000000L)
                                    .duration(40000).keyframe(i % 10 == 0).data(data).build());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS), "producers must finish under tight budget");
            pool.shutdown();
        }
        try (ContainerReader reader = ContainerReader.open(path)) {
            for (int t = 0; t < trackCount; t++) {
                assertEquals(framesPerTrack, reader.track(trackIds[t]).frameCount());
            }
        }
    }
}
