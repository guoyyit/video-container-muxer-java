package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keyframe index and random access by presentation time. */
class SeekTest {

    @TempDir
    Path dir;

    private static final int VIDEO_FRAMES = 60; // keyframes at n = 0, 12, 24, 36, 48

    private Path writeContainer() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        Path file = dir.resolve("seek.vcmx");
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks,
                TestMedia.videoFramesPresentationOrder(1, VIDEO_FRAMES),
                TestMedia.audioFrames(2, 40)).forEach(muxer::writeFrame);
        muxer.close();
        return file;
    }

    /** pts (video timescale) of presentation index n. */
    private static long videoPts(int n) {
        return (n + TestMedia.B_FRAME_DELAY) * TestMedia.FRAME_DURATION;
    }

    @Test
    void seekToExactKeyframeAndMidGop() {
        Path file = writeContainer();
        Demuxer d = new Demuxer(file);

        // exact keyframe hit: presentation index 24 -> keyframe
        SeekResult r = d.seek(1, videoPts(24));
        assertEquals(SeekStatus.EXACT, r.status());
        assertEquals(videoPts(24), r.keyframePts());
        assertEquals(videoPts(24), r.targetPts());

        // mid-GOP request that lands exactly on a non-keyframe
        r = d.seek(1, videoPts(27));
        assertEquals(SeekStatus.EXACT, r.status());
        assertEquals(videoPts(24), r.keyframePts(), "must back up to the previous keyframe");
        assertEquals(videoPts(27), r.targetPts());

        // request inside a gap between frames (half a frame past index 27)
        r = d.seek(1, videoPts(27) + TestMedia.FRAME_DURATION / 2);
        assertEquals(SeekStatus.IN_GAP, r.status());
        assertEquals(videoPts(24), r.keyframePts());
        assertEquals(videoPts(28), r.targetPts(), "target is the first frame after the gap");
        d.close();
    }

    @Test
    void seekIsDeterministicAndRestartsDecodingAtKeyframe() {
        Path file = writeContainer();
        long request = videoPts(30);
        long firstPts = -1;
        for (int i = 0; i < 3; i++) {
            Demuxer d = new Demuxer(file);
            SeekResult r = d.seek(1, request);
            assertEquals(videoPts(24), r.keyframePts());
            // after a successful seek the stream continues from the keyframe
            Optional<Frame> first = d.nextFrame();
            assertTrue(first.isPresent());
            assertEquals(1, first.get().trackId());
            if (i == 0) {
                firstPts = first.get().pts();
            } else {
                assertEquals(firstPts, first.get().pts(),
                        "repeated seeks must produce identical results");
            }
            d.close();
        }
        assertEquals(videoPts(24), firstPts);
    }

    @Test
    void seekEdgeCasesAreDistinguishable() {
        Path file = writeContainer();
        Demuxer d = new Demuxer(file);

        // before the first frame/keyframe of the video track
        SeekResult r = d.seek(1, videoPts(0) - 1);
        assertEquals(SeekStatus.BEFORE_FIRST_KEYFRAME, r.status());

        // negative time
        r = d.seek(1, -5);
        assertEquals(SeekStatus.OUT_OF_RANGE, r.status());

        // beyond the end of the track
        r = d.seek(1, videoPts(VIDEO_FRAMES));
        assertEquals(SeekStatus.OUT_OF_RANGE, r.status());

        // audio track: every frame is a keyframe, exact hit works
        r = d.seek(2, 5 * TestMedia.AUDIO_BLOCK);
        assertEquals(SeekStatus.EXACT, r.status());
        assertEquals(5 * TestMedia.AUDIO_BLOCK, r.keyframePts());
        d.close();
    }

    @Test
    void trackWithoutKeyframesReportsNoKeyframes() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1));
        Path file = dir.resolve("nokey.vcmx");
        Muxer muxer = new Muxer(file, tracks, MuxerConfig.defaults());
        // all frames non-key
        for (int i = 0; i < 10; i++) {
            muxer.writeFrame(new Frame(1, i * TestMedia.FRAME_DURATION,
                    i * TestMedia.FRAME_DURATION, TestMedia.FRAME_DURATION, false,
                    TestMedia.payload(1, i, 100)));
        }
        muxer.close();

        Demuxer d = new Demuxer(file);
        SeekResult r = d.seek(1, 5 * TestMedia.FRAME_DURATION);
        assertEquals(SeekStatus.NO_KEYFRAMES, r.status());
        d.close();
    }
}
