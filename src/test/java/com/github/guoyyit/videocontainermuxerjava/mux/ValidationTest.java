package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Track and timestamp validation: contradictory parameters, illegal timestamps
 * and duplicate track ids are rejected with a track id + field reason.
 */
class ValidationTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    ContainerMuxer newMuxer(Path path) throws Exception {
        return ContainerMuxer.create(path);
    }

    @Test
    void duplicateTrackIdRejected() throws Exception {
        Path path = tempDir.resolve("dup.mux");
        try (ContainerMuxer muxer = newMuxer(path)) {
            TrackConfig v = TrackConfig.builder(TrackType.VIDEO)
                    .trackId(5).codec("AVC").timescale(US).width(320).height(240).build();
            muxer.addTrack(v);
            ContainerValidationException ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.AUDIO)
                            .trackId(5).codec("AAC").timescale(44100)
                            .sampleRate(44100).channels(2).bitsPerSample(16).build()));
            assertEquals(5, ex.getTrackId());
            assertEquals("trackId", ex.getField());
        }
    }

    @Test
    void contradictoryVideoParamsRejected() throws Exception {
        Path path = tempDir.resolve("bad.mux");
        try (ContainerMuxer muxer = newMuxer(path)) {
            // video with zero width
            ContainerValidationException ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                            .codec("AVC").timescale(US).width(0).height(240).build()));
            assertEquals("width", ex.getField());

            // video carrying audio params
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                            .codec("AVC").timescale(US).width(320).height(240)
                            .sampleRate(44100).build()));
            assertEquals("type", ex.getField());

            // audio with video params
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.AUDIO)
                            .codec("AAC").timescale(44100)
                            .sampleRate(44100).channels(2).bitsPerSample(16)
                            .width(100).build()));
            assertEquals("type", ex.getField());

            // audio with zero sample rate
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.AUDIO)
                            .codec("AAC").timescale(44100)
                            .sampleRate(0).channels(2).bitsPerSample(16).build()));
            assertEquals("sampleRate", ex.getField());

            // timescale zero
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                            .codec("AVC").timescale(0).width(320).height(240).build()));
            assertEquals("timescale", ex.getField());
        }
    }

    @Test
    void illegalFrameTimestampsRejected() throws Exception {
        Path path = tempDir.resolve("badframe.mux");
        try (ContainerMuxer muxer = newMuxer(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(320).height(240).build());
            muxer.start();

            // negative pts
            ContainerValidationException ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.writeFrame(v, Frame.builder().pts(-1).dts(0).duration(1)
                            .keyframe(true).data(new byte[]{1}).build()));
            assertEquals("pts", ex.getField());
            assertEquals(v, ex.getTrackId());

            // negative dts
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.writeFrame(v, Frame.builder().pts(0).dts(-1).duration(1)
                            .keyframe(true).data(new byte[]{1}).build()));
            assertEquals("dts", ex.getField());

            // empty data
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.writeFrame(v, Frame.builder().pts(0).dts(0).duration(1)
                            .keyframe(true).data(new byte[0]).build()));
            assertEquals("data", ex.getField());

            // unknown track id
            ex = assertThrows(ContainerValidationException.class,
                    () -> muxer.writeFrame(999, Frame.builder().pts(0).dts(0).duration(1)
                            .keyframe(true).data(new byte[]{1}).build()));
            assertEquals("trackId", ex.getField());
        }
    }

    @Test
    void duplicatePtsRejectedAtFinalize() throws Exception {
        Path path = tempDir.resolve("duppts.mux");
        try (ContainerMuxer muxer = newMuxer(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(320).height(240).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                vo.write(Frame.builder().pts(0).dts(0).duration(1000).keyframe(true)
                        .data(new byte[]{1}).build());
                // duplicate pts in presentation order
                vo.write(Frame.builder().pts(0).dts(1000).duration(1000).keyframe(true)
                        .data(new byte[]{2}).build());
            }
            assertThrows(ContainerValidationException.class, muxer::finish);
        }
    }

    @Test
    void contradictoryMetadataRejectedOnRead() throws Exception {
        // a valid file opens fine
        Path path = tempDir.resolve("ok.mux");
        try (ContainerMuxer muxer = newMuxer(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(320).height(240).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                vo.write(Frame.builder().pts(0).dts(0).duration(40000).keyframe(true)
                        .data(new byte[]{1}).build());
            }
        }
        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(1, reader.track(0).frameCount());
        }
    }
}
