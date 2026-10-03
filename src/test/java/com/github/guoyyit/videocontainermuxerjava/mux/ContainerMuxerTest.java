package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContainerMuxerTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    static List<Frame> videoFrames(int count, long timescale, long frameDuration,
                                   int keyframeInterval) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long pts = i * frameDuration;
            byte[] data = ("video-frame-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            frames.add(Frame.builder()
                    .pts(pts).dts(pts).duration(frameDuration)
                    .keyframe(i % keyframeInterval == 0)
                    .data(data).build());
        }
        return frames;
    }

    static List<Frame> audioFrames(int count, long timescale, long samplesPerPacket) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long pts = i * samplesPerPacket;
            byte[] data = ("audio-packet-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            frames.add(Frame.builder()
                    .pts(pts).dts(pts).duration(samplesPerPacket)
                    .keyframe(true)
                    .data(data).build());
        }
        return frames;
    }

    static TrackConfig videoConfig() {
        return TrackConfig.builder(TrackType.VIDEO)
                .codec("AVC").timescale(US)
                .width(1920).height(1080)
                .build();
    }

    static TrackConfig audioConfig() {
        return TrackConfig.builder(TrackType.AUDIO)
                .codec("AAC").timescale(44100)
                .sampleRate(44100).channels(2).bitsPerSample(16)
                .build();
    }

    static void mux(Path path, List<Frame> video, List<Frame> audio) throws Exception {
        try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
            int v = muxer.addTrack(videoConfig());
            int a = muxer.addTrack(audioConfig());
            muxer.start();
            try (TrackOutput vo = muxer.output(v); TrackOutput ao = muxer.output(a)) {
                for (Frame f : video) vo.write(f);
                for (Frame f : audio) ao.write(f);
            }
        }
    }

    @Test
    void roundTripsTracksAndFrames() throws Exception {
        Path path = tempDir.resolve("roundtrip.mux");
        List<Frame> video = videoFrames(30, US, 33333, 10);
        List<Frame> audio = audioFrames(100, 44100, 1024);
        mux(path, video, audio);

        try (ContainerReader reader = ContainerReader.open(path)) {
            assertEquals(2, reader.tracks().size());
            TrackInfo v = reader.track(0);
            assertEquals(TrackType.VIDEO, v.type());
            assertEquals("AVC", v.codec());
            assertEquals(US, v.timescale());
            assertEquals(1920, v.width());
            assertEquals(1080, v.height());
            assertEquals(30, v.frameCount());
            assertEquals(3, v.keyframeCount());
            assertEquals(29 * 33333 + 33333, v.durationTicks());

            TrackInfo a = reader.track(1);
            assertEquals(TrackType.AUDIO, a.type());
            assertEquals(44100, a.sampleRate());
            assertEquals(2, a.channels());
            assertEquals(16, a.bitsPerSample());
            assertEquals(100, a.frameCount());
            assertEquals(100, a.keyframeCount()); // audio is all keyframes
            assertEquals(100 * 1024, a.durationTicks());

            // container duration = max of track durations (audio longer here: ~2.32s vs 1s)
            long expectedUs = Math.max(v.durationUs(), a.durationUs());
            assertEquals(expectedUs, reader.durationUs());
            assertEquals(2321995L, reader.durationUs());

            // video frames in presentation order, timestamps + payload unchanged
            Iterator<Frame> it = reader.frames(0);
            int i = 0;
            long prevPts = Long.MIN_VALUE;
            while (it.hasNext()) {
                Frame f = it.next();
                assertEquals(video.get(i).pts(), f.pts());
                assertEquals(video.get(i).dts(), f.dts());
                assertEquals(video.get(i).duration(), f.duration());
                assertArrayEquals(video.get(i).data(), f.data());
                assertTrue(f.pts() >= prevPts);
                prevPts = f.pts();
                i++;
            }
            assertEquals(30, i);

            // audio
            it = reader.frames(1);
            i = 0;
            while (it.hasNext()) {
                Frame f = it.next();
                assertArrayEquals(audio.get(i).data(), f.data());
                assertEquals(audio.get(i).pts(), f.pts());
                i++;
            }
            assertEquals(100, i);
        }
    }

    @Test
    void repeatedMuxIsByteIdentical() throws Exception {
        Path p1 = tempDir.resolve("a.mux");
        Path p2 = tempDir.resolve("b.mux");
        List<Frame> video = videoFrames(20, US, 40000, 5);
        List<Frame> audio = audioFrames(40, 44100, 1024);
        mux(p1, video, audio);
        mux(p2, video, audio);
        assertArrayEquals(Files.readAllBytes(p1), Files.readAllBytes(p2));
    }

    @Test
    void durationUsesLongestTrack() throws Exception {
        Path path = tempDir.resolve("dur.mux");
        // audio longer than video
        List<Frame> video = videoFrames(10, US, 100000, 2); // 1s
        List<Frame> audio = audioFrames(200, 44100, 1024);   // ~4.6s
        mux(path, video, audio);
        try (ContainerReader reader = ContainerReader.open(path)) {
            long videoUs = (10L * 100000 * US) / US;
            long audioUs = (200L * 1024 * US) / 44100;
            assertEquals(videoUs, reader.track(0).durationUs());
            assertEquals(audioUs, reader.track(1).durationUs());
            assertEquals(Math.max(videoUs, audioUs), reader.durationUs());
        }
    }
}
