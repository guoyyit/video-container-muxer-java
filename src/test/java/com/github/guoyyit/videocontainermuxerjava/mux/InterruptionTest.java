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
 * Cancellation / interruption: a partially written file must be detected as
 * incomplete on reopen, repeated opens must fail stably, and a rewrite must
 * not depend on the damaged tail.
 */
class InterruptionTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    static List<Frame> frames(int count, long dur) {
        List<Frame> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Frame.builder().pts(i * dur).dts(i * dur).duration(dur)
                    .keyframe(i % 5 == 0)
                    .data(("frame-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .build());
        }
        return list;
    }

    static void fullMux(Path path, List<Frame> frames) throws Exception {
        try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(320).height(240).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                for (Frame f : frames) vo.write(f);
            }
        }
    }

    @Test
    void cancelledWriteIsDetectedAsIncomplete() throws Exception {
        Path path = tempDir.resolve("partial.mux");
        List<Frame> frames = frames(50, 40000);

        ContainerMuxer muxer = ContainerMuxer.create(path);
        muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                .codec("AVC").timescale(US).width(320).height(240).build());
        muxer.start();
        try (TrackOutput vo = muxer.output(0)) {
            // write only half the frames, then cancel
            for (int i = 0; i < 25; i++) {
                vo.write(frames.get(i));
            }
        }
        muxer.cancel();
        muxer.close();

        assertTrue(Files.exists(path));
        long partialSize = Files.size(path);
        assertTrue(partialSize > 0, "partial file must contain header+frames");

        // reopen: incomplete, not corrupt
        ContainerIncompleteException ex = assertThrows(ContainerIncompleteException.class,
                () -> ContainerReader.open(path));
        assertTrue(ex.getMessage().contains("incomplete"));

        // repeated open fails stably
        for (int i = 0; i < 3; i++) {
            assertThrows(ContainerIncompleteException.class, () -> ContainerReader.open(path),
                    "repeated open must fail stably");
        }
    }

    @Test
    void rewriteAfterInterruptionIgnoresOldTail() throws Exception {
        Path path = tempDir.resolve("rewrite.mux");
        List<Frame> frames = frames(40, 40000);

        // first attempt: cancelled after a few frames
        ContainerMuxer muxer = ContainerMuxer.create(path);
        muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                .codec("AVC").timescale(US).width(320).height(240).build());
        muxer.start();
        try (TrackOutput vo = muxer.output(0)) {
            for (int i = 0; i < 10; i++) {
                vo.write(frames.get(i));
            }
        }
        muxer.cancel();
        muxer.close();
        assertThrows(ContainerIncompleteException.class, () -> ContainerReader.open(path));

        // rewrite: fresh muxer truncates and writes the full content
        fullMux(path, frames);

        // now readable, complete, correct
        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(40, reader.track(0).frameCount());
            Iterator<Frame> it = reader.frames(0);
            int i = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                assertEquals(frames.get(i).pts(), f.pts());
                assertArrayEquals(frames.get(i).data(), f.data());
                i++;
            }
            assertEquals(40, i);
        }
    }

    @Test
    void cancelBeforeStartLeavesNoValidContainer() throws Exception {
        Path path = tempDir.resolve("early.mux");
        ContainerMuxer muxer = ContainerMuxer.create(path);
        muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                .codec("AVC").timescale(US).width(320).height(240).build());
        muxer.cancel();
        muxer.close();
        // file may exist (truncated) but must not be a valid container
        if (Files.exists(path)) {
            assertThrows(Exception.class, () -> ContainerReader.open(path));
        }
    }
}
