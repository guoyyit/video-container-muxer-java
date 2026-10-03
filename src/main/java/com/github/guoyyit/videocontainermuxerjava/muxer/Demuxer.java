package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * Streaming reader for VCMX containers.
 *
 * <p>Opening a file scans chunk headers sequentially (payloads are skipped,
 * never loaded), building the keyframe index and per-track statistics in
 * O(number of frames) small records — payload bytes are never held in memory.
 * Frame payloads are read one at a time from the current cursor, and each
 * chunk's CRC-32 is verified when it is read.
 *
 * <p>Integrity is decided deterministically at open time and never changes
 * for a given file: a missing/invalid trailer means the writer was cancelled
 * ({@link ContainerStatus#INCOMPLETE}); a malformed chunk means
 * {@link ContainerStatus#CORRUPT}. Frames before the damaged tail stay
 * readable; the damaged tail is never reported as valid content.
 *
 * <p>Not thread-safe: use from one thread or synchronise externally.
 */
public final class Demuxer implements Closeable {

    private final Path input;
    private final RandomAccessFile raf;
    private final List<TrackSpec> tracks;
    private final Map<Integer, TrackSpec> tracksById = new HashMap<>();
    private final Map<Integer, Integer> trackPosById = new HashMap<>();

    private final ContainerStatus status;
    /** Offset one past the last byte known to be valid (chunks region end). */
    private final long validDataEnd;

    /** Per track (parallel to sorted tracks): keyframe {pts, chunkOffset}. */
    private final List<List<long[]>> keyframeIndex;
    private final long[] trackEndPts;   // track timescale units, -1 when empty
    private final long[] trackFrames;

    private long cursor;                // read position for nextFrame
    private boolean closed;

    public Demuxer(Path input) {
        this.input = input;
        ContainerStatus st;
        long dataEnd;
        try {
            this.raf = new RandomAccessFile(input.toFile(), "r");
            this.tracks = ContainerFormat.readHeader(raf); // RandomAccessFile is a DataInput
            for (int i = 0; i < tracks.size(); i++) {
                tracksById.put(tracks.get(i).trackId(), tracks.get(i));
                trackPosById.put(tracks.get(i).trackId(), i);
            }
            this.keyframeIndex = new ArrayList<>();
            for (int i = 0; i < tracks.size(); i++) {
                keyframeIndex.add(new ArrayList<>());
            }
            this.trackEndPts = new long[tracks.size()];
            java.util.Arrays.fill(trackEndPts, -1);
            this.trackFrames = new long[tracks.size()];

            long fileLength = raf.length();
            this.firstChunkOffset = raf.getFilePointer();
            long offset = firstChunkOffset;
            st = ContainerStatus.INCOMPLETE;
            dataEnd = offset;
            boolean done = false;
            while (!done) {
                int magic;
                try {
                    magic = raf.readByte();
                } catch (EOFException e) {
                    break; // ran out of file without trailer: INCOMPLETE
                }
                if (magic == ContainerFormat.FOOTER_MAGIC) {
                    // footer + trailer follow; validate trailer structure
                    long footerOffset = offset;
                    FooterInfo footer = readFooterAndTrailer(footerOffset, fileLength);
                    if (footer == null) {
                        st = ContainerStatus.INCOMPLETE;
                    } else if (!footer.offsetMatches) {
                        st = ContainerStatus.CORRUPT;
                    } else {
                        st = footer.complete ? ContainerStatus.COMPLETE : ContainerStatus.INCOMPLETE;
                    }
                    dataEnd = footerOffset;
                    done = true;
                } else if (magic == ContainerFormat.CHUNK_MAGIC) {
                    ContainerFormat.ChunkHeader h = readChunkHeaderBody(offset);
                    long chunkEnd = offset + h.totalLength();
                    if (chunkEnd > fileLength) {
                        // truncated tail / half-written frame: INCOMPLETE
                        st = ContainerStatus.INCOMPLETE;
                        dataEnd = offset;
                        done = true;
                    } else if (trackPosById.containsKey(h.trackId())) {
                        recordChunk(h);
                        dataEnd = chunkEnd;
                        raf.seek(chunkEnd);
                        offset = chunkEnd;
                    } else {
                        // chunk references a track not in the header
                        st = ContainerStatus.CORRUPT;
                        dataEnd = offset;
                        done = true;
                    }
                } else {
                    st = ContainerStatus.CORRUPT;
                    dataEnd = offset;
                    done = true;
                }
            }
        } catch (CorruptContainerException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open " + input, e);
        }
        this.status = st;
        this.validDataEnd = dataEnd;
        // position the read cursor at the first chunk
        this.cursor = firstChunkOffset;
    }

    // ------------------------------------------------------------- scanning

    private final long firstChunkOffset;

    private ContainerFormat.ChunkHeader readChunkHeaderBody(long offset) throws IOException {
        int trackId = raf.readInt();
        long pts = raf.readLong();
        long dts = raf.readLong();
        long duration = raf.readLong();
        int flags = raf.readByte();
        int payloadLength = raf.readInt();
        if (payloadLength <= 0 || payloadLength > 256 * 1024 * 1024) {
            throw new CorruptContainerException(
                    "implausible payload length " + payloadLength + " at offset " + offset);
        }
        return new ContainerFormat.ChunkHeader(trackId, pts, dts, duration,
                (flags & ContainerFormat.FLAG_KEYFRAME) != 0, payloadLength, offset);
    }

    private void recordChunk(ContainerFormat.ChunkHeader h) {
        int pos = trackPosById.get(h.trackId());
        if (h.keyFrame()) {
            keyframeIndex.get(pos).add(new long[]{h.pts(), h.chunkOffset()});
        }
        long end = h.pts() + h.duration();
        if (end > trackEndPts[pos]) {
            trackEndPts[pos] = end;
        }
        trackFrames[pos]++;
    }

    private record FooterInfo(boolean offsetMatches, boolean complete) {
    }

    /** Reads footer (skipped) and trailer; null when structurally absent. */
    private FooterInfo readFooterAndTrailer(long footerOffset, long fileLength) {
        try {
            int count = raf.readInt();
            if (count != tracks.size()) {
                return new FooterInfo(false, false);
            }
            for (int i = 0; i < count; i++) {
                raf.readInt();              // trackId
                int kfCount = raf.readInt();
                if (kfCount < 0 || kfCount > 100_000_000) {
                    return new FooterInfo(false, false);
                }
                raf.seek(raf.getFilePointer() + 16L * kfCount + 8 + 8);
            }
            if (raf.getFilePointer() + ContainerFormat.TRAILER_LENGTH > fileLength) {
                return null; // truncated trailer
            }
            long storedOffset = raf.readLong();
            int completeFlag = raf.readByte();
            byte[] magic = new byte[4];
            raf.readFully(magic);
            if (!java.util.Arrays.equals(magic, ContainerFormat.MAGIC)) {
                return null;
            }
            return new FooterInfo(storedOffset == footerOffset, completeFlag == 1);
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- info

    public ContainerStatus status() {
        return status;
    }

    public List<TrackSpec> tracks() {
        return tracks;
    }

    /**
     * Container duration in microseconds. Defined as the maximum, over all
     * tracks, of (last frame pts + duration): tracks need not end at the same
     * time, and the container covers the longest one.
     */
    public long durationMicros() {
        long max = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (trackEndPts[i] >= 0) {
                max = Math.max(max, ContainerFormat.toMicros(trackEndPts[i], tracks.get(i).timescale()));
            }
        }
        return max;
    }

    /** Per-track duration in microseconds, or -1 when the track has no frames. */
    public long trackDurationMicros(int trackId) {
        int pos = requireTrack(trackId);
        if (trackEndPts[pos] < 0) {
            return -1;
        }
        return ContainerFormat.toMicros(trackEndPts[pos], tracks.get(pos).timescale());
    }

    public long trackFrameCount(int trackId) {
        return trackFrames[requireTrack(trackId)];
    }

    private int requireTrack(int trackId) {
        Integer pos = trackPosById.get(trackId);
        if (pos == null) {
            throw new ValidationException(trackId, "trackId", "no such track in container");
        }
        return pos;
    }

    // -------------------------------------------------------------- reading

    /**
     * Reads the next frame at the cursor, in the file's deterministic
     * (pts, trackId) presentation order. Returns empty at the end of the
     * valid region; a truncated or half-written tail is never surfaced.
     *
     * @throws CorruptContainerException if the chunk's CRC-32 does not match
     */
    public Optional<Frame> nextFrame() {
        ensureOpen();
        try {
            if (cursor >= validDataEnd) {
                return Optional.empty();
            }
            raf.seek(cursor);
            int magic = raf.readByte();
            if (magic != ContainerFormat.CHUNK_MAGIC) {
                throw new CorruptContainerException(
                        "expected chunk at offset " + cursor + ", got magic " + magic);
            }
            ContainerFormat.ChunkHeader h = readChunkHeaderBody(cursor);
            byte[] payload = new byte[h.payloadLength()];
            raf.readFully(payload);
            int storedCrc = raf.readInt();
            CRC32 crc = new CRC32();
            crc.update(ContainerFormat.chunkBody(
                    new Frame(h.trackId(), h.pts(), h.dts(), h.duration(), h.keyFrame(), payload)));
            if ((int) crc.getValue() != storedCrc) {
                throw new CorruptContainerException(
                        "CRC mismatch for chunk at offset " + cursor);
            }
            cursor += h.totalLength();
            return Optional.of(new Frame(h.trackId(), h.pts(), h.dts(), h.duration(),
                    h.keyFrame(), payload));
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + input, e);
        }
    }

    // --------------------------------------------------------------- seek

    /**
     * Seeks a track by presentation time (in the track's timescale units).
     *
     * <p>The result is deterministic: the keyframe with the largest pts not
     * exceeding the request is located via the in-memory index, then frames
     * are scanned forward from that keyframe to classify the request as
     * {@link SeekStatus#EXACT} or {@link SeekStatus#IN_GAP}. On success the
     * read cursor is repositioned to that keyframe so decoding restarts there.
     */
    public SeekResult seek(int trackId, long pts) {
        ensureOpen();
        int pos = requireTrack(trackId);
        TrackSpec spec = tracks.get(pos);
        if (pts < 0 || (trackEndPts[pos] >= 0 && pts >= trackEndPts[pos])) {
            return new SeekResult(SeekStatus.OUT_OF_RANGE, -1, -1);
        }
        List<long[]> kfs = keyframeIndex.get(pos);
        if (kfs.isEmpty()) {
            return new SeekResult(SeekStatus.NO_KEYFRAMES, -1, -1);
        }
        // largest keyframe pts <= requested
        long[] chosen = null;
        for (long[] kf : kfs) {
            if (kf[0] <= pts) {
                chosen = kf;
            } else {
                break;
            }
        }
        if (chosen == null) {
            return new SeekResult(SeekStatus.BEFORE_FIRST_KEYFRAME, -1, -1);
        }
        // scan forward from the keyframe to the first frame of this track
        // with frame pts >= requested pts
        try {
            long scan = chosen[1];
            long targetPts = -1;
            while (scan < validDataEnd) {
                raf.seek(scan);
                if (raf.readByte() != ContainerFormat.CHUNK_MAGIC) {
                    throw new CorruptContainerException("expected chunk at offset " + scan);
                }
                ContainerFormat.ChunkHeader h = readChunkHeaderBody(scan);
                if (h.trackId() == trackId && h.pts() >= pts) {
                    targetPts = h.pts();
                    break;
                }
                scan += h.totalLength();
            }
            // reposition the read cursor at the keyframe: decoding restarts here
            cursor = chosen[1];
            SeekStatus st = (targetPts == pts) ? SeekStatus.EXACT : SeekStatus.IN_GAP;
            return new SeekResult(st, chosen[0], targetPts);
        } catch (IOException e) {
            throw new UncheckedIOException("failed seeking in " + input, e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("demuxer is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                raf.close();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
