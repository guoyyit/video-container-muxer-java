package com.github.guoyyit.videocontainermuxerjava.demo;

import com.github.guoyyit.videocontainermuxerjava.mux.*;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Self-contained demonstration: generates in-process test material, muxes it
 * to a container, reads it back, seeks, and reports timing and memory.
 *
 * <p>Run with: {@code mvn compile exec:java
 * -Dexec.mainClass=com.github.guoyyit.videocontainermuxerjava.demo.MuxerDemoRunner}
 */
public final class MuxerDemoRunner {

    private static final long US = 1_000_000L;
    private static final long VIDEO_FRAME_DURATION = 40_000L; // 25 fps
    private static final long AUDIO_SAMPLES_PER_PACKET = 1024L;

    public static void main(String[] args) throws Exception {
        Path path = Files.createTempFile("muxer-demo-", ".mux");
        try {
            run(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void run(Path path) throws Exception {
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        Runtime rt = Runtime.getRuntime();

        int videoFrames = 3000;  // ~2 minutes at 25 fps
        int audioPackets = (int) ((videoFrames * VIDEO_FRAME_DURATION * 44100L)
                / (US * AUDIO_SAMPLES_PER_PACKET));

        System.out.println("=== Container Muxer Demo ===");
        System.out.printf("Input: %d video frames (%.1f s @25fps), %d audio packets (%.1f s @44.1kHz)%n",
                videoFrames, videoFrames * VIDEO_FRAME_DURATION / (double) US,
                audioPackets, audioPackets * AUDIO_SAMPLES_PER_PACKET / 44100.0);

        // ---- generate material ----
        List<Frame> video = new ArrayList<>();
        for (int i = 0; i < videoFrames; i++) {
            video.add(Frame.builder()
                    .pts(i * VIDEO_FRAME_DURATION).dts(i * VIDEO_FRAME_DURATION)
                    .duration(VIDEO_FRAME_DURATION)
                    .keyframe(i % 25 == 0)
                    .data(("video-frame-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        List<Frame> audio = new ArrayList<>();
        for (int i = 0; i < audioPackets; i++) {
            audio.add(Frame.builder()
                    .pts(i * AUDIO_SAMPLES_PER_PACKET).dts(i * AUDIO_SAMPLES_PER_PACKET)
                    .duration(AUDIO_SAMPLES_PER_PACKET).keyframe(true)
                    .data(("audio-packet-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }

        // ---- mux (concurrent producers, single writer) ----
        System.gc();
        long memBefore = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        AtomicLong enqueued = new AtomicLong();
        try (ContainerMuxer muxer = ContainerMuxer.create(path,
                MuxerConfig.builder().maxPendingBytes(8L << 20).maxFrameSize(1 << 20).build())) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(1920).height(1080).build());
            int a = muxer.addTrack(TrackConfig.builder(TrackType.AUDIO)
                    .codec("AAC").timescale(44100).sampleRate(44100).channels(2).bitsPerSample(16)
                    .build());
            muxer.start();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch done = new CountDownLatch(2);
            submitProducer(pool, muxer, v, video, done, enqueued);
            submitProducer(pool, muxer, a, audio, done, enqueued);
            done.await(5, TimeUnit.MINUTES);
            pool.shutdown();
        }
        long t1 = System.nanoTime();
        System.gc();
        long memAfter = rt.totalMemory() - rt.freeMemory();
        long fileSize = Files.size(path);
        System.out.printf("Mux:   %d ms, file %.2f MiB, heap delta ~%.2f MiB (enqueued %d frames)%n",
                (t1 - t0) / 1_000_000, fileSize / (double) (1 << 20),
                (memAfter - memBefore) / (double) (1 << 20), enqueued.get());

        // ---- read back + seek ----
        long t2 = System.nanoTime();
        try (ContainerReader reader = ContainerReader.open(path)) {
            long t3 = System.nanoTime();
            System.out.printf("Open+validate: %d ms%n", (t3 - t2) / 1_000_000);

            TrackInfo vt = reader.track(0);
            TrackInfo at = reader.track(1);
            System.out.printf("Tracks: video %d frames / %d keyframes, duration %.2f s; "
                            + "audio %d frames, duration %.2f s%n",
                    vt.frameCount(), vt.keyframeCount(), vt.durationUs() / 1_000_000.0,
                    at.frameCount(), at.durationUs() / 1_000_000.0);
            System.out.printf("Container duration: %.2f s%n", reader.durationUs() / 1_000_000.0);

            // sequential read (streaming)
            long t4 = System.nanoTime();
            long count = 0;
            Iterator<Frame> it = reader.frames(0);
            while (it.hasNext()) {
                it.next();
                count++;
            }
            long t5 = System.nanoTime();
            System.out.printf("Sequential read of %d video frames: %d ms (%.0f frames/s)%n",
                    count, (t5 - t4) / 1_000_000, count * 1_000_000.0 / Math.max(1, t5 - t4));

            // random-access seeks
            long t6 = System.nanoTime();
            int hits = 0;
            for (long pts = 0; pts < vt.durationTicks(); pts += 1_000_000L) {
                SeekOutcome o = reader.seek(0, pts);
                if (o.isHit()) hits++;
            }
            long t7 = System.nanoTime();
            System.out.printf("%d seeks (1s apart): %d ms (%.2f us/seek), %d hits%n",
                    (vt.durationTicks() / US), (t7 - t6) / 1_000_000,
                    (t7 - t6) / 1000.0 / Math.max(1, vt.durationTicks() / US), hits);

            // resume from a seek point
            Iterator<Frame> resume = reader.framesFrom(0, 60_000_000L); // 60 s
            Frame first = resume.next();
            System.out.printf("Resume from 60s: first frame pts=%d (keyframe=%b)%n",
                    first.pts(), first.keyframe());
        }
        System.out.println("=== Demo complete ===");
    }

    private static void submitProducer(ExecutorService pool, ContainerMuxer muxer, int trackId,
                                       List<Frame> frames, CountDownLatch done, AtomicLong enqueued) {
        pool.submit(() -> {
            try (TrackOutput out = muxer.output(trackId)) {
                for (Frame f : frames) {
                    out.write(f);
                    enqueued.incrementAndGet();
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
}
