package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Out-of-order arrival, pts/dts divergence, monotonic pts, duration rules. */
class ReorderTest {

    @TempDir
    Path dir;

    @Test
    void decodeOrderArrivalIsReorderedToPresentationOrder() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1));
        List<Frame> decodeOrder = TestMedia.videoFramesDecodeOrder(1, 24);
        // sanity: input really is out of presentation order
        boolean sawPtsDecrease = false;
        for (int i = 1; i < decodeOrder.size(); i++) {
            if (decodeOrder.get(i).pts() < decodeOrder.get(i - 1).pts()) {
                sawPtsDecrease = true;
            }
        }
        assertTrue(sawPtsDecrease, "test input must arrive out of order");

        Path file = dir.resolve("reorder.vcmx");
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        decodeOrder.forEach(muxer::writeFrame);
        muxer.close();

        Demuxer demuxer = new Demuxer(file);
        List<Frame> out = new ArrayList<>();
        Optional<Frame> f;
        while ((f = demuxer.nextFrame()).isPresent()) {
            out.add(f.get());
        }
        demuxer.close();

        assertEquals(decodeOrder.size(), out.size());
        // presentation time never goes backwards
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i).pts() >= out.get(i - 1).pts(),
                    "pts must be monotonic at index " + i);
        }
        // adjacent presentation intervals match the input cadence exactly
        for (int i = 1; i < out.size(); i++) {
            assertEquals(TestMedia.FRAME_DURATION, out.get(i).pts() - out.get(i - 1).pts(),
                    "frame interval must be preserved at index " + i);
        }
        // pts/dts relationship preserved per frame
        for (Frame frame : out) {
            assertTrue(frame.dts() <= frame.pts());
        }
    }

    @Test
    void durationComesFromContentAndHandlesMisalignedTrackEnds() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        int videoCount = 25;  // ends at (25-1+2)*3600 + 3600 = 27*3600 ticks = 1.08s
        int audioCount = 10;  // ends at 10*1024/48000 = 0.2133s
        Path file = dir.resolve("duration.vcmx");
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks,
                TestMedia.videoFramesPresentationOrder(1, videoCount),
                TestMedia.audioFrames(2, audioCount)).forEach(muxer::writeFrame);
        muxer.close();

        Demuxer demuxer = new Demuxer(file);
        long expectedVideoMicros = (videoCount - 1 + TestMedia.B_FRAME_DELAY + 1)
                * TestMedia.FRAME_DURATION * 1_000_000L / TestMedia.VIDEO_TIMESCALE;
        long expectedAudioMicros = audioCount * TestMedia.AUDIO_BLOCK * 1_000_000L
                / TestMedia.AUDIO_TIMESCALE;
        assertEquals(expectedVideoMicros, demuxer.trackDurationMicros(1));
        assertEquals(expectedAudioMicros, demuxer.trackDurationMicros(2));
        // container duration = the longest track, even when ends are misaligned
        assertEquals(Math.max(expectedVideoMicros, expectedAudioMicros), demuxer.durationMicros());
        assertEquals(videoCount, demuxer.trackFrameCount(1));
        assertEquals(audioCount, demuxer.trackFrameCount(2));
        demuxer.close();
    }
}
