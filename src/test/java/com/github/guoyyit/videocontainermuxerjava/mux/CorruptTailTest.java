package com.github.guoyyit.videocontainermuxerjava.mux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Corruption detection: a flipped byte, a truncated tail or a half frame must
 * be reported as corrupt/incomplete, never as valid content.
 */
class CorruptTailTest {

    @TempDir
    Path tempDir;

    private static final long US = 1_000_000L;

    Path writeValidContainer(String name) throws Exception {
        Path path = tempDir.resolve(name);
        try (ContainerMuxer muxer = ContainerMuxer.create(path)) {
            int v = muxer.addTrack(TrackConfig.builder(TrackType.VIDEO)
                    .codec("AVC").timescale(US).width(320).height(240).build());
            muxer.start();
            try (TrackOutput vo = muxer.output(v)) {
                for (int i = 0; i < 20; i++) {
                    vo.write(Frame.builder().pts(i * 40000L).dts(i * 40000L).duration(40000)
                            .keyframe(i % 5 == 0)
                            .data(("frame-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            .build());
                }
            }
        }
        return path;
    }

    @Test
    void flippedByteDetectedAsCorrupt() throws Exception {
        Path path = writeValidContainer("flip.mux");
        byte[] bytes = Files.readAllBytes(path);
        // flip a byte in the frame data region (after header, before trailer)
        int flipPos = bytes.length / 2;
        bytes[flipPos] ^= 0x01;
        Files.write(path, bytes);

        ContainerCorruptException ex = assertThrows(ContainerCorruptException.class,
                () -> ContainerReader.open(path));
        assertTrue(ex.getMessage().toLowerCase().contains("crc"),
                "should report CRC mismatch: " + ex.getMessage());
    }

    @Test
    void truncatedTailDetectedAsIncomplete() throws Exception {
        Path path = writeValidContainer("trunc.mux");
        byte[] bytes = Files.readAllBytes(path);
        // cut off the trailer (last 29 bytes) -> no completeness flag
        byte[] truncated = new byte[bytes.length - ContainerFormat.TRAILER_SIZE];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        Files.write(path, truncated);

        assertThrows(ContainerIncompleteException.class, () -> ContainerReader.open(path));
    }

    @Test
    void halfFrameDetectedAsCorrupt() throws Exception {
        Path path = writeValidContainer("half.mux");
        byte[] bytes = Files.readAllBytes(path);
        // cut into the last frame block (remove trailer + half of last frame)
        int cut = ContainerFormat.TRAILER_SIZE + 10;
        byte[] truncated = new byte[bytes.length - cut];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        Files.write(path, truncated);

        // either incomplete (no trailer) or corrupt (truncated block) — both are failures
        assertThrows(Exception.class, () -> ContainerReader.open(path));
    }

    @Test
    void garbageAfterTrailerDetectedAsIncomplete() throws Exception {
        Path path = writeValidContainer("garbage.mux");
        byte[] bytes = Files.readAllBytes(path);
        // append garbage after the trailer — the trailer is no longer at the end,
        // so the file must be detected as incomplete (never silently accepted)
        byte[] withGarbage = new byte[bytes.length + 100];
        System.arraycopy(bytes, 0, withGarbage, 0, bytes.length);
        for (int i = bytes.length; i < withGarbage.length; i++) {
            withGarbage[i] = (byte) 0xAB;
        }
        Files.write(path, withGarbage);

        assertThrows(ContainerIncompleteException.class, () -> ContainerReader.open(path));
    }
}
