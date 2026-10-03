package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Streaming over a large input: memory must stay bounded and no full-content
 * read is required.
 */
class StreamingTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    @Test
    void largeInputMuxesAndReadsWithBoundedMemory() throws Exception {
        Path path = tempDir.resolve("large.mux");
        int frameCount = 50_000;
        long dur = 40_000;

        // small pending budget: producers block frequently, memory stays bounded
        try (ContainerMuxer muxer = ContainerMuxer.create(path,
                MuxerConfig.builder()
                        .maxPendingBytes(256 << 10)  // 256 KiB
                        .maxPendingFramesPerTrack(64)
                        .maxFrameSize(4 << 10)
                        .build())) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(640).height(480).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                for (int i = 0; i < frameCount; i++) {
                    byte[] data = new byte[128];
                    vo.write(Frame.builder()
                            .pts(i * dur).dts(i * dur).duration(dur)
                            .keyframe(i % 25 == 0).data(data).build());
                }
            }
        }

        // read back: frame count correct, timestamps correct
        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(frameCount, reader.track(0).frameCount());
            Iterator<Frame> it = reader.frames(0);
            long prev = Long.MIN_VALUE;
            int count = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                assertTrue(f.pts() > prev, "pts must be strictly increasing");
                prev = f.pts();
                count++;
            }
            assertEquals(frameCount, count);

            // seek works on the large file
            SeekOutcome o = reader.seek(0, 1_000_000_000L); // 1000s
            assertEquals(SeekStatus.HIT, o.status());
            assertEquals(1_000_000_000L - (1_000_000_000L % (25 * dur)), o.resumedPts());
        }
    }
}
