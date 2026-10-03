package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * Binary layout of the VCMX container (all integers big-endian):
 *
 * <pre>
 * header   := MAGIC version trackCount track*
 * track    := trackId type codec timescale width height sampleRate channels meta
 * chunk    := CHUNK_MAGIC trackId pts dts duration flags payloadLen payload crc32
 *             (crc32 covers trackId..payload, i.e. everything after CHUNK_MAGIC)
 * footer   := FOOTER_MAGIC trackIndex* trackStats*
 * trailer  := footerOffset completeFlag MAGIC   (fixed 13 bytes at end of file)
 * </pre>
 *
 * Tracks in the header and footer are always sorted by trackId and chunks are
 * written in (pts, trackId) order, so identical input yields identical bytes.
 */
final class ContainerFormat {

    static final byte[] MAGIC = {'V', 'C', 'M', 'X'};
    static final int VERSION = 1;
    static final byte CHUNK_MAGIC = 'C';
    static final byte FOOTER_MAGIC = 'I';
    static final int TRAILER_LENGTH = 8 + 1 + 4;
    /** chunk header without magic: 4 + 8 + 8 + 8 + 1 + 4 = 33 bytes. */
    static final int CHUNK_HEADER_LENGTH = 33;
    static final int FLAG_KEYFRAME = 1;

    private ContainerFormat() {
    }

    // ---------------------------------------------------------------- header

    static void writeHeader(DataOutput out, List<TrackSpec> tracks) throws IOException {
        out.write(MAGIC);
        out.writeInt(VERSION);
        List<TrackSpec> sorted = sortedTracks(tracks);
        out.writeInt(sorted.size());
        for (TrackSpec t : sorted) {
            writeTrack(out, t);
        }
    }

    static List<TrackSpec> readHeader(DataInput in) throws IOException {
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("bad magic, not a VCMX container");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("unsupported VCMX version " + version);
        }
        int count = in.readInt();
        if (count <= 0 || count > 4096) {
            throw new IOException("implausible track count " + count);
        }
        List<TrackSpec> tracks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tracks.add(readTrack(in));
        }
        TrackValidator.validateTracks(tracks);
        return tracks;
    }

    private static void writeTrack(DataOutput out, TrackSpec t) throws IOException {
        out.writeInt(t.trackId());
        out.writeByte(t.type() == TrackType.VIDEO ? 0 : 1);
        writeString(out, t.codec());
        out.writeInt(t.timescale());
        out.writeInt(t.width());
        out.writeInt(t.height());
        out.writeInt(t.sampleRate());
        out.writeInt(t.channels());
        Map<String, String> meta = new TreeMap<>(t.metadata());
        out.writeInt(meta.size());
        for (Map.Entry<String, String> e : meta.entrySet()) {
            writeString(out, e.getKey());
            writeString(out, e.getValue());
        }
    }

    private static TrackSpec readTrack(DataInput in) throws IOException {
        int trackId = in.readInt();
        int typeByte = in.readByte();
        TrackType type = switch (typeByte) {
            case 0 -> TrackType.VIDEO;
            case 1 -> TrackType.AUDIO;
            default -> throw new IOException("unknown track type " + typeByte);
        };
        String codec = readString(in);
        int timescale = in.readInt();
        int width = in.readInt();
        int height = in.readInt();
        int sampleRate = in.readInt();
        int channels = in.readInt();
        int metaCount = in.readInt();
        if (metaCount < 0 || metaCount > 1024) {
            throw new IOException("implausible metadata count " + metaCount);
        }
        Map<String, String> meta = new TreeMap<>();
        for (int i = 0; i < metaCount; i++) {
            meta.put(readString(in), readString(in));
        }
        return new TrackSpec(trackId, type, codec, timescale, width, height,
                sampleRate, channels, meta);
    }

    // ---------------------------------------------------------------- chunks

    /** Writes one chunk and returns its CRC-32 (also stored in the chunk). */
    static void writeChunk(DataOutput out, Frame frame) throws IOException {
        out.writeByte(CHUNK_MAGIC);
        byte[] body = chunkBody(frame);
        out.write(body);
        CRC32 crc = new CRC32();
        crc.update(body);
        out.writeInt((int) crc.getValue());
    }

    static byte[] chunkBody(Frame frame) {
        byte[] payload = frame.payload();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(CHUNK_HEADER_LENGTH + payload.length);
        buf.putInt(frame.trackId());
        buf.putLong(frame.pts());
        buf.putLong(frame.dts());
        buf.putLong(frame.duration());
        buf.put(frame.keyFrame() ? (byte) FLAG_KEYFRAME : (byte) 0);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    /** Parsed chunk header; payload is not loaded. */
    record ChunkHeader(int trackId, long pts, long dts, long duration,
                       boolean keyFrame, int payloadLength, long chunkOffset) {
        /** Total bytes on disk: magic + header + payload + crc. */
        long totalLength() {
            return 1 + CHUNK_HEADER_LENGTH + payloadLength + 4L;
        }
    }

    // ---------------------------------------------------------------- footer

    /**
     * @param keyframeIndex per track (same order as {@code tracks}, sorted by
     *                      trackId) the sorted list of {pts, chunkOffset} pairs
     * @param trackEndPts   per track the end time (max pts + duration), -1 if empty
     * @param trackFrames   per track the number of frames written
     */
    static void writeFooter(DataOutput out, List<TrackSpec> tracks,
                            List<List<long[]>> keyframeIndex,
                            long[] trackEndPts, long[] trackFrames) throws IOException {
        List<TrackSpec> sorted = sortedTracks(tracks);
        out.writeByte(FOOTER_MAGIC);
        out.writeInt(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            TrackSpec t = sorted.get(i);
            out.writeInt(t.trackId());
            List<long[]> kfs = keyframeIndex.get(i);
            out.writeInt(kfs.size());
            for (long[] kf : kfs) {
                out.writeLong(kf[0]); // pts
                out.writeLong(kf[1]); // chunk offset
            }
            out.writeLong(trackEndPts[i]);
            out.writeLong(trackFrames[i]);
        }
    }

    /** Writes the fixed-size trailer; {@code complete} is false when aborted. */
    static void writeTrailer(DataOutput out, long footerOffset, boolean complete) throws IOException {
        out.writeLong(footerOffset);
        out.writeByte(complete ? 1 : 0);
        out.write(MAGIC);
    }

    // ---------------------------------------------------------------- misc

    static List<TrackSpec> sortedTracks(List<TrackSpec> tracks) {
        return tracks.stream()
                .sorted(java.util.Comparator.comparingInt(TrackSpec::trackId))
                .toList();
    }

    private static void writeString(DataOutput out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInput in) throws IOException {
        int len = in.readShort() & 0xFFFF;
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Converts a track timestamp to microseconds without overflow. */
    static long toMicros(long ts, int timescale) {
        return Math.multiplyExact(ts, 1_000_000L) / timescale;
    }
}
