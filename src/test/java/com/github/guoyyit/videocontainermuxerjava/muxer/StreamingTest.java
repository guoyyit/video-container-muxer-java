package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Large-input streaming: writing, reading and seeking must stay bounded in
 * memory and scale linearly in time. Prints measured timings for the docs.
 */
class StreamingTest {

    @TempDir
    Path dir;

    @Test
    void largeContainerStreamsWithBoundedMemory() throws Exception {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        int videoFrames = 6_000;   // 240 s of video at 25 fps
        int audioFrames = 11_250;  // 240 s of audio at ~46.9 fps of blocks
        Path file = dir.resolve("large.vcmx");

        long t0 = System.nanoTime();
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        // interleave on the fly; payloads are larger than in unit tests so
        // the file is meaningfully big (~60 MB)
        List<Frame> video = TestMedia.videoFramesPresentationOrder(1, videoFrames);
        List<Frame> audio = TestMedia.audioFrames(2, audioFrames);
        video.replaceAll(f -> new Frame(f.trackId(), f.pts(), f.dts(), f.duration(),
                f.keyFrame(), TestMedia.payload(1, f.pts() / TestMedia.FRAME_DURATION, 8_192)));
        audio.replaceAll(f -> new Frame(f.trackId(), f.pts(), f.dts(), f.duration(),
                f.keyFrame(), TestMedia.payload(2, f.pts() / TestMedia.AUDIO_BLOCK, 1_024)));
        int vi = 0, ai = 0;
        while (vi < video.size() || ai < audio.size()) {
            long vPts = vi < video.size()
                    ? video.get(vi).pts() * 1_000_000L / TestMedia.VIDEO_TIMESCALE : Long.MAX_VALUE;
            long aPts = ai < audio.size()
                    ? audio.get(ai).pts() * 1_000_000L / TestMedia.AUDIO_TIMESCALE : Long.MAX_VALUE;
            if (vPts <= aPts) {
                muxer.writeFrame(video.get(vi++));
            } else {
                muxer.writeFrame(audio.get(ai++));
            }
            if (vi == video.size() && video.size() > 0) {
                muxer.endOfTrack(1);
                video = List.of(); // avoid repeated signalling
            }
            if (ai == audio.size() && audio.size() > 0) {
                muxer.endOfTrack(2);
                audio = List.of();
            }
        }
        muxer.close();
        long writeMs = (System.nanoTime() - t0) / 1_000_000;
        long fileSize = Files.size(file);
        assertTrue(fileSize > 50_000_000, "file should be sizeable, was " + fileSize);

        // open + index scan: time and heap footprint
        System.gc();
        long heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        t0 = System.nanoTime();
        Demuxer d = new Demuxer(file);
        long openMs = (System.nanoTime() - t0) / 1_000_000;
        long heapAfterOpen = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long indexBytes = heapAfterOpen - heapBefore;
        assertEquals(ContainerStatus.COMPLETE, d.status());
        assertTrue(indexBytes < fileSize / 10,
                "open must not load content: index " + indexBytes + " vs file " + fileSize);

        // streaming read of the whole file: frames come one at a time
        t0 = System.nanoTime();
        int count = 0;
        Optional<Frame> f;
        while ((f = d.nextFrame()).isPresent()) {
            count++;
        }
        long readMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(videoFrames + audioFrames, count);

        // random access: seek latency is independent of file position
        t0 = System.nanoTime();
        SeekResult r = d.seek(1, (5_999L + TestMedia.B_FRAME_DELAY) * TestMedia.FRAME_DURATION);
        long seekLastMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.ok());
        t0 = System.nanoTime();
        r = d.seek(1, (100L + TestMedia.B_FRAME_DELAY) * TestMedia.FRAME_DURATION);
        long seekEarlyMs = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(r.ok());
        d.close();

        System.out.printf(
                "STREAMING file=%.1fMB frames=%d write=%dms open=%dms readAll=%dms "
                        + "seekEarly=%dms seekLast=%dms indexHeap=%.1fKB%n",
                fileSize / 1e6, count, writeMs, openMs, readMs,
                seekEarlyMs, seekLastMs, indexBytes / 1024.0);
    }
}
