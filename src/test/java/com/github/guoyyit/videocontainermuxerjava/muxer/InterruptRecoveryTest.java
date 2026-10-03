package com.github.guoyyit.videocontainermuxerjava.muxer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Cancelled/interrupted writes, truncated tails, corrupted payloads. */
class InterruptRecoveryTest {

    @TempDir
    Path dir;

    private List<TrackSpec> tracks() {
        return List.of(TestMedia.videoTrack(1), TestMedia.audioTrack(2));
    }

    private Path writeComplete(String name) {
        Path file = dir.resolve(name);
        Muxer muxer = new Muxer(file, tracks(), MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks(),
                TestMedia.videoFramesPresentationOrder(1, 30),
                TestMedia.audioFrames(2, 20)).forEach(muxer::writeFrame);
        muxer.close();
        return file;
    }

    @Test
    void abortedWriteIsDetectedAsIncompleteAndReadableUpToTheEnd() {
        Path file = dir.resolve("aborted.vcmx");
        Muxer muxer = new Muxer(file, tracks(), MuxerConfig.defaults());
        List<Frame> frames = RoundTripTest.interleaveByPts(tracks(),
                TestMedia.videoFramesPresentationOrder(1, 30),
                TestMedia.audioFrames(2, 20));
        // write only the first half, then cancel
        frames.subList(0, frames.size() / 2).forEach(muxer::writeFrame);
        muxer.abort();

        // opening twice must give the same, stable verdict
        for (int i = 0; i < 2; i++) {
            Demuxer d = new Demuxer(file);
            assertEquals(ContainerStatus.INCOMPLETE, d.status());
            int count = 0;
            while (d.nextFrame().isPresent()) {
                count++;
            }
            assertTrue(count > 0, "frames written before the abort must be readable");
            d.close();
        }
    }

    @Test
    void truncatedTailIsNotReportedAsValidContent() throws Exception {
        Path complete = writeComplete("complete.vcmx");
        byte[] bytes = Files.readAllBytes(complete);

        // cut the file in the middle of a chunk (half-written frame)
        Path truncated = dir.resolve("truncated.vcmx");
        int cut = bytes.length - ContainerFormat.TRAILER_LENGTH - 40;
        Files.write(truncated, java.util.Arrays.copyOf(bytes, cut));

        for (int i = 0; i < 2; i++) {
            Demuxer d = new Demuxer(truncated);
            assertEquals(ContainerStatus.INCOMPLETE, d.status(),
                    "missing trailer must be reported as incomplete");
            // every frame returned must pass its checksum; the half-written
            // tail must simply never appear
            int count = 0;
            Optional<Frame> f;
            while ((f = d.nextFrame()).isPresent()) {
                count++;
            }
            assertTrue(count > 0);
            d.close();
        }

        // cut only the trailer region: footer offset no longer verifiable
        Path noTrailer = dir.resolve("notrailer.vcmx");
        Files.write(noTrailer, java.util.Arrays.copyOf(bytes, bytes.length - 5));
        Demuxer d = new Demuxer(noTrailer);
        assertEquals(ContainerStatus.INCOMPLETE, d.status());
        d.close();
    }

    @Test
    void corruptedPayloadFailsChecksumDeterministically() throws Exception {
        Path complete = writeComplete("crc.vcmx");
        byte[] bytes = Files.readAllBytes(complete);

        // flip a byte inside the first chunk's payload (after the header)
        Path corrupt = dir.resolve("corrupt.vcmx");
        byte[] damaged = bytes.clone();
        // find first chunk: header size is where first 'C' magic sits; scan
        int firstChunk = -1;
        for (int i = 8; i < damaged.length; i++) {
            if (damaged[i] == 'C') {
                firstChunk = i;
                break;
            }
        }
        assertTrue(firstChunk > 0);
        int payloadOffset = firstChunk + 1 + ContainerFormat.CHUNK_HEADER_LENGTH;
        damaged[payloadOffset + 5] ^= 0xFF;
        Files.write(corrupt, damaged);

        // status is COMPLETE (structure intact) but reading the damaged chunk
        // must fail with a checksum error, identically on every attempt
        for (int i = 0; i < 2; i++) {
            Demuxer d = new Demuxer(corrupt);
            assertEquals(ContainerStatus.COMPLETE, d.status());
            assertThrows(CorruptContainerException.class, () -> {
                while (d.nextFrame().isPresent()) {
                    // drain
                }
            });
            d.close();
        }
    }

    @Test
    void rewriteAfterAbortDoesNotDependOnDamagedTail() throws Exception {
        Path file = dir.resolve("rewrite.vcmx");
        Muxer muxer = new Muxer(file, tracks(), MuxerConfig.defaults());
        RoundTripTest.interleaveByPts(tracks(),
                TestMedia.videoFramesPresentationOrder(1, 10),
                TestMedia.audioFrames(2, 5)).forEach(muxer::writeFrame);
        muxer.abort();
        assertEquals(ContainerStatus.INCOMPLETE, new Demuxer(file).status());

        // rewrite from scratch on the same path: a brand new Muxer truncates
        // the file, so no state from the damaged tail can leak through
        Muxer fresh = new Muxer(file, tracks(), MuxerConfig.defaults());
        List<Frame> frames = RoundTripTest.interleaveByPts(tracks(),
                TestMedia.videoFramesPresentationOrder(1, 30),
                TestMedia.audioFrames(2, 20));
        frames.forEach(fresh::writeFrame);
        fresh.close();

        Demuxer d = new Demuxer(file);
        assertEquals(ContainerStatus.COMPLETE, d.status());
        int count = 0;
        while (d.nextFrame().isPresent()) {
            count++;
        }
        assertEquals(frames.size(), count);
        d.close();
    }

    @Test
    void garbageFileIsRejected() throws Exception {
        Path garbage = dir.resolve("garbage.vcmx");
        Files.write(garbage, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        assertThrows(java.io.UncheckedIOException.class, () -> new Demuxer(garbage));
    }
}
