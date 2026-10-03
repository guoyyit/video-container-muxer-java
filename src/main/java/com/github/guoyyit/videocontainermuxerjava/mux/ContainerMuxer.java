package com.github.guoyyit.videocontainermuxerjava.mux;

import java.io.BufferedOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.zip.CRC32;

/**
 * In-process container muxer.
 *
 * <p>Multiple producer threads feed frames through bounded per-track queues; a
 * single writer thread interleaves them in decode order (DTS) and writes the
 * container file. Output bytes depend only on the input frames, never on
 * thread scheduling or enqueue order, so repeated muxes are byte-identical.
 *
 * <p>Memory used by in-flight frame payloads is bounded globally by
 * {@link MuxerConfig#maxPendingBytes()} and per track by
 * {@link MuxerConfig#maxPendingFramesPerTrack()}; producers block (backpressure)
 * when the budgets are exhausted, so a slow track can never misalign the
 * interleaving or grow memory without bound.
 */
public final class ContainerMuxer implements AutoCloseable {

    private final Path path;
    private final MuxerConfig config;
    private final Object lock = new Object();
    private final List<TrackState> tracks = new ArrayList<>();
    private final Map<Integer, TrackState> byId = new HashMap<>();

    private RafOutputStream rafOut;
    private CountingOutputStream counting;
    private DataOutputStream out;
    private Thread writerThread;

    private boolean started;
    private boolean cancelled;
    private boolean finished;
    private boolean closed;
    private Throwable failure;

    private long pendingBytes;
    private long headerEndOffset;
    private long dataEndOffset;

    private ContainerMuxer(Path path, MuxerConfig config) {
        this.path = path;
        this.config = config;
    }

    public static ContainerMuxer create(Path path, MuxerConfig config) throws IOException {
        config.validate();
        ContainerMuxer muxer = new ContainerMuxer(path, config);
        // truncate: a fresh mux never reads the old tail
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "rw");
        raf.setLength(0);
        muxer.rafOut = new RafOutputStream(raf);
        muxer.counting = new CountingOutputStream(muxer.rafOut);
        muxer.out = new DataOutputStream(new BufferedOutputStream(muxer.counting, 1 << 16));
        return muxer;
    }

    public static ContainerMuxer create(Path path) throws IOException {
        return create(path, MuxerConfig.defaults());
    }

    public int addTrack(TrackConfig track) {
        synchronized (lock) {
            if (started) {
                throw new ContainerValidationException(track.trackId(), "state",
                        "cannot add tracks after start()");
            }
            if (tracks.size() >= config.maxTracks()) {
                throw new ContainerValidationException(track.trackId(), "trackCount",
                        "too many tracks (max " + config.maxTracks() + ")");
            }
            int id = track.trackId() != null ? track.trackId() : tracks.size();
            if (byId.containsKey(id)) {
                throw new ContainerValidationException(id, "trackId",
                        "duplicate track id " + id);
            }
            track.validate(id);
            TrackState state = new TrackState(id, track);
            tracks.add(state);
            byId.put(id, state);
            return id;
        }
    }

    public TrackOutput output(int trackId) {
        return new TrackOutput(this, trackId);
    }

    public void start() throws IOException {
        synchronized (lock) {
            if (started) {
                throw new IllegalStateException("already started");
            }
            if (cancelled) {
                throw new IllegalStateException("muxer was cancelled");
            }
            started = true;
            writeHeader(out, tracks);
            out.flush();
            rafOut.seek(headerEndOffset);
            writerThread = new Thread(this::runWriter, "container-muxer-writer");
            writerThread.setDaemon(true);
            writerThread.start();
        }
    }

    public void writeFrame(int trackId, Frame frame) throws InterruptedException {
        TrackState state;
        synchronized (lock) {
            if (cancelled) {
                throw new ContainerValidationException(trackId, "state", "muxer is cancelled");
            }
            if (!started) {
                throw new ContainerValidationException(trackId, "state", "muxer not started");
            }
            if (finished) {
                throw new ContainerValidationException(trackId, "state", "muxer already finished");
            }
            state = byId.get(trackId);
            if (state == null) {
                throw new ContainerValidationException(trackId, "trackId", "unknown track id " + trackId);
            }
            if (state.eos) {
                throw new ContainerValidationException(trackId, "state", "track already ended");
            }
            validateFrame(state, frame);
        }
        enqueue(state, frame);
    }

    private void validateFrame(TrackState state, Frame frame) {
        int id = state.id;
        if (frame == null || frame.data() == null) {
            throw new ContainerValidationException(id, "data", "frame data must not be null");
        }
        if (frame.data().length == 0) {
            throw new ContainerValidationException(id, "data", "frame data must not be empty");
        }
        if (frame.data().length > config.maxFrameSize()) {
            throw new ContainerValidationException(id, "data",
                    "frame size " + frame.data().length + " exceeds maxFrameSize "
                            + config.maxFrameSize());
        }
        if (frame.pts() < 0 || frame.pts() > Long.MAX_VALUE / 2) {
            throw new ContainerValidationException(id, "pts",
                    "pts out of range: " + frame.pts());
        }
        if (frame.dts() < 0 || frame.dts() > Long.MAX_VALUE / 2) {
            throw new ContainerValidationException(id, "dts",
                    "dts out of range: " + frame.dts());
        }
        if (frame.duration() < 0 || frame.duration() > 0xFFFFFFFFL) {
            throw new ContainerValidationException(id, "duration",
                    "duration out of range: " + frame.duration());
        }
    }

    private void enqueue(TrackState state, Frame frame) throws InterruptedException {
        int len = frame.data().length;
        synchronized (lock) {
            while (!cancelled && !state.eos
                    && (pendingBytes + len > config.maxPendingBytes()
                        || state.heap.size() >= config.maxPendingFramesPerTrack())) {
                lock.wait();
            }
            if (cancelled) {
                throw new ContainerValidationException(state.id, "state", "muxer is cancelled");
            }
            if (state.eos) {
                throw new ContainerValidationException(state.id, "state", "track already ended");
            }
            EnqueuedFrame ef = new EnqueuedFrame(frame, state.seq++, state);
            state.heap.add(ef);
            pendingBytes += len;
            if (frame.dts() > state.maxDtsEnqueued) {
                state.maxDtsEnqueued = frame.dts();
            }
            lock.notifyAll();
        }
    }

    public void endTrack(int trackId) {
        synchronized (lock) {
            TrackState state = byId.get(trackId);
            if (state == null) {
                throw new ContainerValidationException(trackId, "trackId", "unknown track id " + trackId);
            }
            state.eos = true;
            lock.notifyAll();
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            lock.notifyAll();
        }
    }

    public void finish() throws IOException {
        Thread t;
        synchronized (lock) {
            if (!started) {
                throw new IllegalStateException("muxer not started");
            }
            t = writerThread;
        }
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for writer", e);
        }
        synchronized (lock) {
            if (failure != null) {
                if (failure instanceof RuntimeException re) {
                    throw re;
                }
                if (failure instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException("muxer writer failed", failure);
            }
            if (cancelled) {
                throw new ContainerException("muxer was cancelled; output is incomplete");
            }
            finished = true;
        }
    }

    @Override
    public void close() {
        boolean allEnded;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            allEnded = !tracks.isEmpty() && tracks.stream().allMatch(t -> t.eos);
        }
        if (!started) {
            try {
                rafOut.close();
            } catch (IOException ignored) {
                // nothing written yet
            }
            return;
        }
        if (!finished && !cancelled) {
            if (allEnded) {
                try {
                    finish();
                } catch (Exception e) {
                    cancel();
                }
            } else {
                cancel();
            }
        }
        if (writerThread != null) {
            try {
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            rafOut.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------
    // writer thread
    // ------------------------------------------------------------------

    private void runWriter() {
        try {
            while (true) {
                EnqueuedFrame next;
                synchronized (lock) {
                    while (true) {
                        if (cancelled) {
                            return;
                        }
                        next = pickEligible();
                        if (next == DONE) {
                            finalizeContainer();
                            return;
                        }
                        if (next != null) {
                            break;
                        }
                        lock.wait();
                    }
                    next.owner.heap.remove(next);
                    pendingBytes -= next.frame().data().length;
                    lock.notifyAll();
                }
                writeFrameBlock(next.owner, next);
            }
        } catch (Throwable t) {
            synchronized (lock) {
                failure = t;
                cancelled = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * Picks the next frame to write. A frame is eligible only when every track
     * has either reached end-of-stream or has enqueued a frame whose DTS is at
     * least (candidate DTS + that track's reorder window). This guarantees that
     * no future frame can interleave before the candidate, while keeping the
     * in-flight set bounded.
     */
    private EnqueuedFrame pickEligible() {
        boolean allEos = true;
        for (TrackState t : tracks) {
            if (!t.eos) {
                allEos = false;
                break;
            }
        }
        boolean allEmpty = true;
        for (TrackState t : tracks) {
            if (!t.heap.isEmpty()) {
                allEmpty = false;
                break;
            }
        }
        if (allEos && allEmpty) {
            return DONE;
        }
        EnqueuedFrame best = null;
        for (TrackState t : tracks) {
            EnqueuedFrame head = t.heap.peek();
            if (head == null) {
                continue;
            }
            boolean eligible = true;
            for (TrackState u : tracks) {
                if (u.eos) {
                    continue;
                }
                if (u.heap.isEmpty()) {
                    eligible = false;
                    break;
                }
                long guarantee = u.maxDtsEnqueued - u.config.reorderWindowTicks();
                if (guarantee < head.frame.dts()) {
                    eligible = false;
                    break;
                }
            }
            if (eligible && (best == null || ENQUEUED_COMPARATOR.compare(head, best) < 0)) {
                best = head;
            }
        }
        return best;
    }

    private void writeFrameBlock(TrackState t, EnqueuedFrame ef) throws IOException {
        Frame f = ef.frame;
        long offset;
        synchronized (lock) {
            out.flush();
            offset = counting.getCount();
        }
        boolean keyframe = f.keyframe() || t.config.type() == TrackType.AUDIO;
        ContainerFormat.writeBlockHeader(out, t.id, f.pts(), f.dts(), f.duration(),
                keyframe, f.data().length);
        out.write(f.data());
        t.frameCount++;
        t.durationTicks = Math.max(t.durationTicks, f.pts() + f.duration());
        if (keyframe) {
            t.keyframeCount++;
            t.index.add(new IndexEntry(t.id, f.pts(), f.dts(), offset,
                    ContainerFormat.BLOCK_HEADER_SIZE + f.data().length, true));
        }
    }

    // ------------------------------------------------------------------
    // finalize
    // ------------------------------------------------------------------

    private void finalizeContainer() throws IOException {
        out.flush();
        dataEndOffset = counting.getCount();

        // Re-read the frame section and validate ordering/duplicates, counts and
        // durations before committing the trailer. Memory used here is bounded by
        // the per-track presentation-reorder window.
        verifyFrameSection();

        // Re-write the header now that counts/durations are known (same length).
        rafOut.seek(0);
        writeHeader(rafOut.raf(), tracks);
        rafOut.seek(dataEndOffset);
        counting.setCount(dataEndOffset);

        // Index block, sorted by (trackId, pts) for deterministic output.
        List<IndexEntry> all = new ArrayList<>();
        for (TrackState t : tracks) {
            all.addAll(t.index);
        }
        all.sort(Comparator.comparingLong((IndexEntry e) -> e.pts).thenComparingInt(e -> e.trackId));
        long indexOffset = counting.getCount();
        out.writeInt(all.size());
        for (IndexEntry e : all) {
            out.writeInt(e.trackId);
            out.writeLong(e.pts);
            out.writeLong(e.dts);
            out.writeLong(e.offset);
            out.writeInt(e.blockSize);
            out.writeByte(ContainerFormat.FLAG_KEYFRAME);
        }
        out.flush();
        long indexLength = counting.getCount() - indexOffset;

        // Trailer: completeness flag + CRC over everything written so far.
        long trailerStart = counting.getCount();
        CRC32 crc = new CRC32();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(path.toFile())) {
            byte[] buf = new byte[1 << 16];
            long remaining = trailerStart;
            while (remaining > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    throw new ContainerCorruptException("file shrank while computing CRC");
                }
                crc.update(buf, 0, n);
                remaining -= n;
            }
        }
        out.write(ContainerFormat.MAGIC);
        out.writeLong(indexOffset);
        out.writeInt((int) indexLength);
        out.writeLong(dataEndOffset);
        out.writeByte(1);
        out.writeInt((int) crc.getValue());
        out.flush();
        rafOut.getFD().sync();
    }

    private void verifyFrameSection() throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path.toFile(), "r")) {
            int n = tracks.size();
            long[] lastDts = new long[n];
            long[] lastPts = new long[n];
            long[] counted = new long[n];
            long[] maxDtsMinusPts = new long[n];
            Arrays.fill(lastDts, Long.MIN_VALUE);
            Arrays.fill(lastPts, Long.MIN_VALUE);
            Arrays.fill(maxDtsMinusPts, Long.MIN_VALUE);
            @SuppressWarnings("unchecked")
            PriorityQueue<long[]>[] minHeaps = new PriorityQueue[n];
            @SuppressWarnings("unchecked")
            PriorityQueue<long[]>[] maxHeaps = new PriorityQueue[n];
            for (int i = 0; i < n; i++) {
                minHeaps[i] = new PriorityQueue<>(Comparator.comparingLong((long[] a) -> a[0]));
                maxHeaps[i] = new PriorityQueue<>(Comparator.comparingLong((long[] a) -> -a[0]));
            }
            long pos = headerEndOffset;
            while (pos < dataEndOffset) {
                raf.seek(pos);
                ContainerFormat.Block b = ContainerFormat.readBlockHeader(raf);
                int ti = trackIndex(b.trackId());
                if (ti < 0) {
                    throw new ContainerCorruptException("block with unknown trackId " + b.trackId());
                }
                if (b.dataSize() < 0 || pos + b.blockSize() > dataEndOffset) {
                    throw new ContainerCorruptException("truncated frame block at offset " + pos);
                }
                if (b.dts() <= lastDts[ti]) {
                    throw new ContainerValidationException(b.trackId(), "dts",
                            "dts not monotonic at offset " + pos + ": " + b.dts()
                                    + " after " + lastDts[ti]);
                }
                lastDts[ti] = b.dts();
                maxDtsMinusPts[ti] = Math.max(maxDtsMinusPts[ti], b.dts() - b.pts());
                counted[ti]++;
                long[] item = {b.pts(), b.dts()};
                minHeaps[ti].add(item);
                maxHeaps[ti].add(item);

                // peek next block's dts to drive the presentation-order emit rule
                long nextDts = Long.MAX_VALUE;
                long nextPos = pos + b.blockSize();
                if (nextPos < dataEndOffset) {
                    raf.seek(nextPos);
                    raf.readInt(); // trackId
                    nextDts = raf.readLong(); // pts
                    nextDts = raf.readLong(); // dts
                }
                long reorderBound = tracks.get(ti).config.presentationReorderTicks();
                while (!minHeaps[ti].isEmpty()) {
                    long minPts = minHeaps[ti].peek()[0];
                    if (nextDts != Long.MAX_VALUE && nextDts <= minPts + reorderBound) {
                        break;
                    }
                    long[] emitted = minHeaps[ti].poll();
                    maxHeaps[ti].remove(emitted);
                    if (emitted[0] <= lastPts[ti]) {
                        throw new ContainerValidationException(b.trackId(), "pts",
                                "pts not non-decreasing in presentation order: " + emitted[0]
                                        + " after " + lastPts[ti]);
                    }
                    lastPts[ti] = emitted[0];
                }
                pos = nextPos;
            }
            for (int ti = 0; ti < n; ti++) {
                long bound = tracks.get(ti).config.presentationReorderTicks();
                while (!minHeaps[ti].isEmpty()) {
                    long[] emitted = minHeaps[ti].poll();
                    if (emitted[0] <= lastPts[ti]) {
                        throw new ContainerValidationException(tracks.get(ti).id, "pts",
                                "pts not non-decreasing in presentation order: " + emitted[0]
                                        + " after " + lastPts[ti]);
                    }
                    lastPts[ti] = emitted[0];
                }
                if (maxDtsMinusPts[ti] > bound) {
                    throw new ContainerValidationException(tracks.get(ti).id, "presentationReorderTicks",
                            "presentation reorder bound exceeded: max(dts-pts)=" + maxDtsMinusPts[ti]
                                    + " > declared " + bound);
                }
                if (counted[ti] != tracks.get(ti).frameCount) {
                    throw new ContainerCorruptException("frame count mismatch for track "
                            + tracks.get(ti).id + ": wrote " + tracks.get(ti).frameCount
                            + " but section contains " + counted[ti]);
                }
            }
        }
    }

    private int trackIndex(int id) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id == id) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // header serialisation
    // ------------------------------------------------------------------

    private void writeHeader(DataOutput out, List<TrackState> trackList) throws IOException {
        out.write(ContainerFormat.MAGIC);
        out.writeShort(ContainerFormat.VERSION);
        out.writeShort(0); // flags
        int headerLength = 10; // version(2) + flags(2) + headerLength(4) + trackCount(2)
        for (TrackState t : trackList) {
            headerLength += trackEntryLength(t.config);
        }
        out.writeInt(headerLength);
        out.writeShort(trackList.size());
        for (TrackState t : trackList) {
            writeTrackEntry(out, t);
        }
        headerEndOffset = 4L + headerLength;
    }

    private int trackEntryLength(TrackConfig c) {
        int len = 4 + 1 + 1 + c.codec().length() + 4 + 8 + 8 + 8 + 4 + 4 + 4 + c.codecPrivate().length;
        len += c.type() == TrackType.VIDEO ? 8 : 6;
        return len;
    }

    private void writeTrackEntry(DataOutput out, TrackState t) throws IOException {
        TrackConfig c = t.config;
        out.writeInt(t.id);
        out.writeByte(c.type().code());
        byte[] codecBytes = c.codec().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.writeByte(codecBytes.length);
        out.write(codecBytes);
        out.writeInt((int) c.timescale());
        out.writeLong(t.durationTicks);
        out.writeLong(t.frameCount);
        out.writeLong(t.keyframeCount);
        out.writeInt((int) c.reorderWindowTicks());
        out.writeInt((int) c.presentationReorderTicks());
        out.writeInt(c.codecPrivate().length);
        out.write(c.codecPrivate());
        if (c.type() == TrackType.VIDEO) {
            out.writeInt(c.width());
            out.writeInt(c.height());
        } else {
            out.writeInt(c.sampleRate());
            out.writeByte(c.channels());
            out.writeByte(c.bitsPerSample());
        }
    }

    // ------------------------------------------------------------------
    // internal types
    // ------------------------------------------------------------------

    private record EnqueuedFrame(Frame frame, long seq, TrackState owner) {
    }

    private record IndexEntry(int trackId, long pts, long dts, long offset,
                              int blockSize, boolean keyframe) {
    }

    private static final Comparator<EnqueuedFrame> ENQUEUED_COMPARATOR =
            Comparator.comparingLong((EnqueuedFrame e) -> e.frame.dts())
                    .thenComparingInt(e -> e.owner.id)
                    .thenComparingLong(e -> e.frame.pts())
                    .thenComparingLong(e -> e.seq);

    private static final EnqueuedFrame DONE = new EnqueuedFrame(null, -1, null);

    private static final class TrackState {
        final int id;
        final TrackConfig config;
        final PriorityQueue<EnqueuedFrame> heap;
        long maxDtsEnqueued = Long.MIN_VALUE;
        boolean eos;
        long seq;
        long frameCount;
        long keyframeCount;
        long durationTicks;
        final List<IndexEntry> index = new ArrayList<>();

        TrackState(int id, TrackConfig config) {
            this.id = id;
            this.config = config;
            this.heap = new PriorityQueue<>(ENQUEUED_COMPARATOR);
        }
    }

    /** Counts bytes actually written to the file (logical position, buffer-independent). */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        long getCount() { return count; }
        void setCount(long c) { count = c; }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException { delegate.flush(); }

        @Override
        public void close() throws IOException { delegate.close(); }
    }

    /** OutputStream over a RandomAccessFile so the writer can seek (header patch). */
    private static final class RafOutputStream extends OutputStream {
        private final java.io.RandomAccessFile raf;

        RafOutputStream(java.io.RandomAccessFile raf) {
            this.raf = raf;
        }

        java.io.RandomAccessFile raf() { return raf; }
        void seek(long pos) throws IOException { raf.seek(pos); }
        long getFilePointer() throws IOException { return raf.getFilePointer(); }
        public void flush() throws IOException { /* flushed via DataOutputStream */ }
        java.io.FileDescriptor getFD() throws IOException { return raf.getFD(); }

        @Override
        public void write(int b) throws IOException { raf.write(b); }

        @Override
        public void write(byte[] b, int off, int len) throws IOException { raf.write(b, off, len); }

        @Override
        public void close() throws IOException { raf.close(); }
    }
}
