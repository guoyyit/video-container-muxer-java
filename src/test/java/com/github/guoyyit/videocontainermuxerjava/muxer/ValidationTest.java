package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Track/timestamp validation rejects bad input with locatable reasons. */
class ValidationTest {

    @TempDir
    Path dir;

    private void expectTrackFailure(List<TrackSpec> tracks, int trackId, String field) {
        ValidationException e = assertThrows(ValidationException.class,
                () -> new Muxer(dir.resolve("bad.vcmx"), tracks, MuxerConfig.defaults()));
        assertEquals(trackId, e.trackId(), "exception must name the offending track");
        assertTrue(e.field().contains(field),
                "exception must name the field, got: " + e.field());
        assertTrue(e.getMessage().contains("track=" + trackId));
    }

    @Test
    void duplicateTrackIdsAreRejected() {
        expectTrackFailure(List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(1)),
                1, "trackId");
    }

    @Test
    void contradictoryTrackParamsAreRejected() {
        // video track with audio parameters
        expectTrackFailure(List.of(new TrackSpec(1, TrackType.VIDEO, "rawvideo",
                90000, 320, 240, 48000, 2, Map.of())), 1, "sampleRate");
        // audio track with video parameters
        expectTrackFailure(List.of(new TrackSpec(2, TrackType.AUDIO, "pcm",
                48000, 320, 240, 48000, 2, Map.of())), 2, "width");
        // video track without dimensions
        expectTrackFailure(List.of(new TrackSpec(3, TrackType.VIDEO, "rawvideo",
                90000, 0, 0, 0, 0, Map.of())), 3, "width");
        // audio track without sample rate
        expectTrackFailure(List.of(new TrackSpec(4, TrackType.AUDIO, "pcm",
                48000, 0, 0, 0, 2, Map.of())), 4, "sampleRate");
        // illegal timescale
        expectTrackFailure(List.of(new TrackSpec(5, TrackType.VIDEO, "rawvideo",
                0, 320, 240, 0, 0, Map.of())), 5, "timescale");
        // blank codec
        expectTrackFailure(List.of(new TrackSpec(6, TrackType.VIDEO, " ",
                90000, 320, 240, 0, 0, Map.of())), 6, "codec");
        // negative track id
        expectTrackFailure(List.of(new TrackSpec(-1, TrackType.VIDEO, "rawvideo",
                90000, 320, 240, 0, 0, Map.of())), -1, "trackId");
    }

    @Test
    void illegalTimestampsAreRejected() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1));
        // negative pts
        ValidationException e = assertThrows(ValidationException.class, () -> {
            Muxer m = new Muxer(dir.resolve("t1.vcmx"), tracks, MuxerConfig.defaults());
            m.writeFrame(new Frame(1, -1, 0, 3600, true, new byte[10]));
        });
        assertEquals(1, e.trackId());
        assertEquals("pts", e.field());

        // dts after pts
        e = assertThrows(ValidationException.class, () -> {
            Muxer m = new Muxer(dir.resolve("t2.vcmx"), tracks, MuxerConfig.defaults());
            m.writeFrame(new Frame(1, 100, 200, 3600, true, new byte[10]));
        });
        assertEquals("dts", e.field());

        // zero duration
        e = assertThrows(ValidationException.class, () -> {
            Muxer m = new Muxer(dir.resolve("t3.vcmx"), tracks, MuxerConfig.defaults());
            m.writeFrame(new Frame(1, 0, 0, 0, true, new byte[10]));
        });
        assertEquals("duration", e.field());

        // empty payload
        e = assertThrows(ValidationException.class, () -> {
            Muxer m = new Muxer(dir.resolve("t4.vcmx"), tracks, MuxerConfig.defaults());
            m.writeFrame(new Frame(1, 0, 0, 3600, true, new byte[0]));
        });
        assertEquals("payload", e.field());

        // frame for an unknown track
        e = assertThrows(ValidationException.class, () -> {
            Muxer m = new Muxer(dir.resolve("t5.vcmx"), tracks, MuxerConfig.defaults());
            m.writeFrame(new Frame(99, 0, 0, 3600, true, new byte[10]));
        });
        assertEquals(99, e.trackId());
    }

    @Test
    void duplicatePtsAndLateFramesAreRejected() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        Muxer m = new Muxer(dir.resolve("dup.vcmx"), tracks, MuxerConfig.defaults());
        m.writeFrame(new Frame(1, 7200, 7200, 3600, true, new byte[10]));
        // same pts again on the same track
        ValidationException e = assertThrows(ValidationException.class,
                () -> m.writeFrame(new Frame(1, 7200, 7200, 3600, true, new byte[10])));
        assertEquals("pts", e.field());

        // far-future frame on both tracks flushes early ones; a frame below
        // the flushed watermark is then rejected as too late
        m.writeFrame(new Frame(1, 7200 + 5_000_000L / 40 * 3600 / 2250, 0, 3600, true, new byte[10]));
        m.writeFrame(new Frame(2, 48000 * 10, 48000 * 10, 1024, true, new byte[10]));
        e = assertThrows(ValidationException.class,
                () -> m.writeFrame(new Frame(1, 3600, 3600, 3600, true, new byte[10])));
        assertEquals("pts", e.field());
        assertTrue(e.getMessage().contains("too late"));
        m.abort();
    }

    @Test
    void frameAfterEndOfTrackIsRejected() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1));
        Muxer m = new Muxer(dir.resolve("eos.vcmx"), tracks, MuxerConfig.defaults());
        m.writeFrame(new Frame(1, 7200, 7200, 3600, true, new byte[10]));
        m.endOfTrack(1);
        assertThrows(ValidationException.class,
                () -> m.writeFrame(new Frame(1, 10800, 10800, 3600, false, new byte[10])));
        m.abort();
    }

    @Test
    void writeAfterCloseIsRejected() {
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1));
        Muxer m = new Muxer(dir.resolve("closed.vcmx"), tracks, MuxerConfig.defaults());
        m.writeFrame(new Frame(1, 7200, 7200, 3600, true, new byte[10]));
        m.close();
        assertThrows(IllegalStateException.class,
                () -> m.writeFrame(new Frame(1, 10800, 10800, 3600, false, new byte[10])));
    }

    @Test
    void validContainerStillReadableAfterValidationOfOthers() {
        // a container that passes validation must actually be readable
        List<TrackSpec> tracks = List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
        Path file = dir.resolve("good.vcmx");
        Muxer m = new Muxer(file, tracks, MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks,
                TestMedia.videoFramesPresentationOrder(1, 10),
                TestMedia.audioFrames(2, 10)).forEach(m::writeFrame);
        m.close();
        Demuxer d = new Demuxer(file);
        assertEquals(ContainerStatus.COMPLETE, d.status());
        d.close();
    }
}
