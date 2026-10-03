package com.github.guoyyit.videocontainermuxerjava.mux;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Streaming container reader.
 *
 * <p>Opening a container validates the trailer completeness flag, the CRC32
 * over the whole file, the header and the keyframe index against the frame
 * blocks. Frames are never all loaded into memory: blocks are read sequentially
 * and only a bounded presentation-reorder buffer is kept per track.
 */
public final class ContainerReader implements AutoCloseable {

    private final Path path;
    private final List<TrackInfo> tracks = new ArrayList<>();
    private final Map<Integer, TrackInfo> byId = new HashMap<>();
    private final Map<Integer, List<IndexEntry>> indexByTrack = new HashMap<>();
    private long headerEndOffset;
    private long dataEndOffset;
    private long durationUs;

    private ContainerReader(Path path) {
        this.path = path;
    }

    public static ContainerReader open(Path path) throws IOException {
        ContainerReader reader = new ContainerReader(path);
        reader.load();
        return reader;
    }

    public Path path() { return path; }

    public List<TrackInfo> tracks() { return List.copyOf(tracks); }

    public TrackInfo track(int trackId) {
        TrackInfo t = byId.get(trackId);
        if (t == null) {
            throw new ContainerValidationException(trackId, "trackId", "unknown track id " + trackId);
        }
        return t;
    }

    /** Container duration in microseconds (max over tracks). */
    public long durationUs() { return durationUs; }

    /**
     * Seeks to a keyframe at or before {@code requestedPts} on the given track.
     * Never loads frame data: only the keyframe index is consulted.
     */
    public SeekOutcome seek(int trackId, long requestedPts) {
        TrackInfo t = track(trackId);
        List<IndexEntry> entries = indexByTrack.getOrDefault(trackId, List.of());
        if (entries.isEmpty()) {
            return SeekOutcome.miss(SeekStatus.NO_KEYFRAME, trackId, requestedPts);
        }
        if (requestedPts < entries.get(0).pts) {
            return SeekOutcome.miss(SeekStatus.BEFORE_FIRST_KEYFRAME, trackId, requestedPts);
        }
        if (requestedPts >= t.durationTicks()) {
            return SeekOutcome.miss(SeekStatus.BEYOND_END, trackId, requestedPts);
        }
        // largest entry with pts <= requestedPts
        int lo = 0, hi = entries.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (entries.get(mid).pts <= requestedPts) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        IndexEntry e = entries.get(lo - 1);
        return SeekOutcome.hit(trackId, requestedPts, e.pts, e.dts, e.offset, e.blockSize);
    }

    /** Iterate all frames of a track in presentation order (streaming). */
    public Iterator<Frame> frames(int trackId) {
        track(trackId); // validates existence
        try {
            return new FrameIterator(path, headerEndOffset, dataEndOffset, trackId,
                    track(trackId).presentationReorderTicks());
        } catch (IOException e) {
            throw new ContainerCorruptException("failed to open frame stream", e);
        }
    }

    /**
     * Iterate frames of a track in presentation order, resuming at the
     * keyframe returned by {@link #seek(int, long)}.
     */
    public Iterator<Frame> framesFrom(int trackId, long requestedPts) {
        SeekOutcome outcome = seek(trackId, requestedPts);
        if (!outcome.isHit()) {
            throw new ContainerException("cannot resume from " + outcome.status()
                    + " for track " + trackId + " at pts " + requestedPts);
        }
        try {
            return new FrameIterator(path, outcome.offset(), dataEndOffset, trackId,
                    track(trackId).presentationReorderTicks());
        } catch (IOException e) {
            throw new ContainerCorruptException("failed to open frame stream", e);
        }
    }

    @Override
    public void close() {
        // all state is heap-resident and file handles are per-iterator
    }

    // ------------------------------------------------------------------
    // loading / validation
    // ------------------------------------------------------------------

    private void load() throws IOException {
        if (!java.nio.file.Files.isRegularFile(path)) {
            throw new ContainerCorruptException("not a regular file: " + path);
        }
        long size = java.nio.file.Files.size(path);
        if (size < ContainerFormat.TRAILER_SIZE + 4) {
            throw new ContainerIncompleteException(path.toString());
        }
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            // ---- trailer ----
            long trailerStart = size - ContainerFormat.TRAILER_SIZE;
            raf.seek(trailerStart);
            byte[] magic = new byte[4];
            raf.readFully(magic);
            long indexOffset = raf.readLong();
            long indexLength = raf.readInt() & 0xFFFFFFFFL;
            long dataEnd = raf.readLong();
            int complete = raf.readUnsignedByte();
            int storedCrc = raf.readInt();
            if (!java.util.Arrays.equals(magic, ContainerFormat.MAGIC) || complete != 1) {
                throw new ContainerIncompleteException(path.toString());
            }
            if (indexOffset < 4 || indexLength < 4 || dataEnd > indexOffset
                    || indexOffset + indexLength > trailerStart) {
                throw new ContainerCorruptException("trailer offsets out of range");
            }
            CRC crc = new CRC();
            crc.updateFile(raf, trailerStart);
            if ((int) crc.value != storedCrc) {
                throw new ContainerCorruptException("CRC mismatch: expected "
                        + Integer.toHexString(storedCrc) + " but computed "
                        + Integer.toHexString((int) crc.value));
            }
            this.dataEndOffset = dataEnd;

            // ---- header ----
            raf.seek(0);
            raf.readFully(magic);
            if (!java.util.Arrays.equals(magic, ContainerFormat.MAGIC)) {
                throw new ContainerCorruptException("bad magic");
            }
            int version = raf.readUnsignedShort();
            if (version != ContainerFormat.VERSION) {
                throw new ContainerCorruptException("unsupported version " + version);
            }
            raf.readUnsignedShort(); // flags
            long headerLength = raf.readInt() & 0xFFFFFFFFL;
            int trackCount = raf.readUnsignedShort();
            if (trackCount < 1 || trackCount > ContainerFormat.MAX_TRACKS) {
                throw new ContainerCorruptException("bad track count " + trackCount);
            }
            this.headerEndOffset = 4L + headerLength;
            if (headerEndOffset > dataEndOffset) {
                throw new ContainerCorruptException("header overlaps frame section");
            }
            for (int i = 0; i < trackCount; i++) {
                TrackInfo info = readTrackEntry(raf);
                if (byId.put(info.trackId(), info) != null) {
                    throw new ContainerCorruptException("duplicate track id " + info.trackId());
                }
                tracks.add(info);
            }

            // ---- index ----
            raf.seek(indexOffset);
            int entryCount = raf.readInt();
            if (entryCount < 0 || entryCount > dataEndOffset / ContainerFormat.BLOCK_HEADER_SIZE) {
                throw new ContainerCorruptException("index entry count out of range: " + entryCount);
            }
            List<IndexEntry> entries = new ArrayList<>(entryCount);
            for (int i = 0; i < entryCount; i++) {
                int trackId = raf.readInt();
                long pts = raf.readLong();
                long dts = raf.readLong();
                long offset = raf.readLong();
                int blockSize = raf.readInt();
                int flags = raf.readUnsignedByte();
                entries.add(new IndexEntry(trackId, pts, dts, offset, blockSize,
                        (flags & ContainerFormat.FLAG_KEYFRAME) != 0));
            }
            validateIndex(entries, dataEnd);

            // ---- frame section cross-check ----
            scanFrameSection(raf, entries, dataEnd);

            for (TrackInfo t : tracks) {
                indexByTrack.put(t.trackId(), entries.stream()
                        .filter(e -> e.trackId == t.trackId())
                        .sorted(Comparator.comparingLong((IndexEntry e) -> e.pts))
                        .toList());
            }
            this.durationUs = tracks.stream().mapToLong(TrackInfo::durationUs).max().orElse(0L);
        }
    }

    private TrackInfo readTrackEntry(RandomAccessFile raf) throws IOException {
        int trackId = raf.readInt();
        int typeCode = raf.readUnsignedByte();
        TrackType type;
        try {
            type = TrackType.fromCode(typeCode);
        } catch (IllegalArgumentException e) {
            throw new ContainerCorruptException("unknown track type " + typeCode);
        }
        int codecLen = raf.readUnsignedByte();
        byte[] codecBytes = new byte[codecLen];
        raf.readFully(codecBytes);
        String codec = new String(codecBytes, java.nio.charset.StandardCharsets.US_ASCII);
        long timescale = raf.readInt() & 0xFFFFFFFFL;
        long durationTicks = raf.readLong();
        long frameCount = raf.readLong();
        long keyframeCount = raf.readLong();
        long reorderWindow = raf.readInt() & 0xFFFFFFFFL;
        long presentationReorder = raf.readInt() & 0xFFFFFFFFL;
        int privLen = raf.readInt();
        if (privLen < 0 || privLen > ContainerFormat.MAX_CODEC_PRIVATE_LEN) {
            throw new ContainerCorruptException("codecPrivate length out of range: " + privLen);
        }
        byte[] codecPrivate = new byte[privLen];
        raf.readFully(codecPrivate);
        TrackInfo.Builder b = TrackInfo.builder()
                .trackId(trackId).type(type).codec(codec).timescale(timescale)
                .durationTicks(durationTicks).frameCount(frameCount)
                .keyframeCount(keyframeCount).reorderWindowTicks(reorderWindow)
                .presentationReorderTicks(presentationReorder).codecPrivate(codecPrivate);
        switch (type) {
            case VIDEO -> {
                b.width(raf.readInt());
                b.height(raf.readInt());
            }
            case AUDIO -> {
                b.sampleRate(raf.readInt());
                b.channels(raf.readUnsignedByte());
                b.bitsPerSample(raf.readUnsignedByte());
            }
        }
        TrackInfo info = b.build();
        // reuse parameter validation to reject contradictory metadata
        TrackConfig.Builder cb = TrackConfig.builder(type).trackId(trackId).codec(codec)
                .timescale(timescale).reorderWindowTicks(reorderWindow)
                .presentationReorderTicks(presentationReorder).codecPrivate(codecPrivate);
        if (type == TrackType.VIDEO) {
            cb.width(info.width()).height(info.height());
        } else {
            cb.sampleRate(info.sampleRate()).channels(info.channels())
                    .bitsPerSample(info.bitsPerSample());
        }
        cb.build().validate(trackId);
        return info;
    }

    private void validateIndex(List<IndexEntry> entries, long dataEnd) throws IOException {
        Map<Integer, Long> lastPtsByTrack = new HashMap<>();
        for (IndexEntry e : entries) {
            TrackInfo t = byId.get(e.trackId);
            if (t == null) {
                throw new ContainerCorruptException("index references unknown track " + e.trackId);
            }
            if (!e.keyframe) {
                throw new ContainerCorruptException("index entry is not a keyframe: " + e);
            }
            if (e.offset < headerEndOffset || e.offset + e.blockSize > dataEnd) {
                throw new ContainerCorruptException("index entry offset out of range: " + e);
            }
            long prev = lastPtsByTrack.getOrDefault(e.trackId, Long.MIN_VALUE);
            if (e.pts <= prev) {
                throw new ContainerCorruptException("index pts not strictly increasing for track "
                        + e.trackId + ": " + e.pts + " after " + prev);
            }
            lastPtsByTrack.put(e.trackId, e.pts);
            // verify the block header at the offset matches the index entry
            try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
                raf.seek(e.offset);
                ContainerFormat.Block b = ContainerFormat.readBlockHeader(raf);
                if (b.trackId() != e.trackId || b.pts() != e.pts || b.dts() != e.dts
                        || !b.keyframe() || b.blockSize() != e.blockSize) {
                    throw new ContainerCorruptException("index entry does not match block at "
                            + e.offset + ": index " + e + " vs block " + b);
                }
            }
        }
    }

    private void scanFrameSection(RandomAccessFile raf, List<IndexEntry> entries, long dataEnd)
            throws IOException {
        Map<Integer, long[]> counts = new HashMap<>();
        Map<Long, IndexEntry> indexByOffset = new HashMap<>();
        for (IndexEntry e : entries) {
            indexByOffset.put(e.offset, e);
        }
        long pos = headerEndOffset;
        while (pos < dataEnd) {
            raf.seek(pos);
            ContainerFormat.Block b = ContainerFormat.readBlockHeader(raf);
            if (b.dataSize() < 0 || pos + b.blockSize() > dataEnd) {
                throw new ContainerCorruptException("truncated frame block at offset " + pos);
            }
            TrackInfo t = byId.get(b.trackId());
            if (t == null) {
                throw new ContainerCorruptException("block with unknown trackId " + b.trackId());
            }
            long[] c = counts.computeIfAbsent(b.trackId(), k -> new long[2]);
            c[0]++;
            if (b.keyframe()) {
                c[1]++;
                IndexEntry e = indexByOffset.get(pos);
                if (e == null) {
                    throw new ContainerCorruptException("keyframe block at " + pos
                            + " has no index entry");
                }
                if (e.pts != b.pts() || e.dts != b.dts()) {
                    throw new ContainerCorruptException("index mismatch at " + pos);
                }
            }
            pos += b.blockSize();
        }
        if (pos != dataEnd) {
            throw new ContainerCorruptException("frame section ends at " + pos
                    + " but trailer declares " + dataEnd);
        }
        for (TrackInfo t : tracks) {
            long[] c = counts.getOrDefault(t.trackId(), new long[2]);
            if (c[0] != t.frameCount()) {
                throw new ContainerCorruptException("frame count mismatch for track " + t.trackId()
                        + ": header " + t.frameCount() + " vs section " + c[0]);
            }
            if (c[1] != t.keyframeCount()) {
                throw new ContainerCorruptException("keyframe count mismatch for track "
                        + t.trackId() + ": header " + t.keyframeCount() + " vs section " + c[1]);
            }
        }
        if (entries.size() != entries.stream().mapToLong(e -> 1).count()) {
            throw new ContainerCorruptException("duplicate index entries");
        }
    }

    private record IndexEntry(int trackId, long pts, long dts, long offset,
                              int blockSize, boolean keyframe) {
    }

    /** Streaming CRC over a RandomAccessFile range. */
    private static final class CRC {
        private final java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        private long value;

        void updateFile(RandomAccessFile raf, long length) throws IOException {
            byte[] buf = new byte[1 << 16];
            long remaining = length;
            raf.seek(0);
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new ContainerCorruptException("file shrank while computing CRC");
                }
                crc32.update(buf, 0, n);
                remaining -= n;
            }
            value = crc32.getValue();
        }
    }
}
