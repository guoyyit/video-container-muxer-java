package com.github.guoyyit.videocontainermuxerjava.mux;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Binary layout constants and low-level block (de)serialisation helpers for the
 * in-process container format ("MUX1").
 *
 * <p>File layout (all integers big-endian):
 * <pre>
 *   [magic 4][header block][frame blocks...][index block][trailer 29]
 * </pre>
 * The trailer carries the completeness flag and a CRC32 over every preceding
 * byte, so an interrupted write (no trailer / bad CRC) is always detectable.
 */
final class ContainerFormat {

    static final byte[] MAGIC = "MUX1".getBytes(StandardCharsets.US_ASCII);
    static final int VERSION = 1;

    static final int TRACK_VIDEO = 1;
    static final int TRACK_AUDIO = 2;

    static final int FLAG_KEYFRAME = 0x01;

    static final int MAX_TRACKS = 64;
    static final int MAX_CODEC_LEN = 64;
    static final int MAX_CODEC_PRIVATE_LEN = 64 * 1024;

    /** u32 trackId + u64 pts + u64 dts + u32 duration + u8 flags + u32 dataSize. */
    static final int BLOCK_HEADER_SIZE = 4 + 8 + 8 + 4 + 1 + 4;

    /** u32 trackId + u64 pts + u64 dts + u64 offset + u32 blockSize + u8 flags. */
    static final int INDEX_ENTRY_SIZE = 4 + 8 + 8 + 8 + 4 + 1;

    /** magic(4) + indexOffset(8) + indexLength(4) + dataEndOffset(8) + complete(1) + crc32(4). */
    static final int TRAILER_SIZE = 4 + 8 + 4 + 8 + 1 + 4;

    private ContainerFormat() {
    }

    static void writeBlockHeader(DataOutput out, int trackId, long pts, long dts,
                                 long duration, boolean keyframe, int dataSize) throws IOException {
        out.writeInt(trackId);
        out.writeLong(pts);
        out.writeLong(dts);
        out.writeInt((int) duration);
        out.writeByte(keyframe ? FLAG_KEYFRAME : 0);
        out.writeInt(dataSize);
    }

    static Block readBlockHeader(DataInput in) throws IOException {
        int trackId = in.readInt();
        long pts = in.readLong();
        long dts = in.readLong();
        long duration = in.readInt() & 0xFFFFFFFFL;
        int flags = in.readUnsignedByte();
        int dataSize = in.readInt();
        return new Block(trackId, pts, dts, duration, (flags & FLAG_KEYFRAME) != 0, dataSize);
    }

    /** A decoded frame-block header. */
    record Block(int trackId, long pts, long dts, long duration, boolean keyframe, int dataSize) {
        int blockSize() {
            return BLOCK_HEADER_SIZE + dataSize;
        }
    }
}
