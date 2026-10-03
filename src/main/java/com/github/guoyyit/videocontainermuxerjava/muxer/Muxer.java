package com.github.guoyyit.videocontainermuxerjava.muxer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Single-writer container muxer.
 *
 * <p>Producers on any number of threads submit frames via {@link #writeFrame};
 * all file I/O is serialised through one lock, so the container is only ever
 * touched by one logical writer and chunks can never interleave incorrectly.
 *
 * <p><b>Reordering and determinism.</b> Frames may arrive out of order
 * (decode order vs presentation order, arbitrary interleaving across tracks).
 * Each track has its own bounded queue; frames are emitted in globally
 * sorted (pts, trackId) order once they can no longer be overtaken, i.e.
 * once every started track's frontier has moved at least
 * {@link MuxerConfig#reorderWindowPts()} beyond them. Because duplicate pts
 * within one track are rejected, the emitted byte stream is fully determined
 * by the input frame set and the track table — never by arrival order or
 * producer concurrency.
 *
 * <p><b>Memory bound and back-pressure.</b> Total buffered bytes are bounded
 * by {@link MuxerConfig#maxBufferedBytes()}: once over the bound, producers
 * block — except the frontier (slowest) track's producer, whose progress is
 * what lets every queue drain; blocking it would deadlock. Memory therefore
 * stays within the bound plus one reorder window of the frontier track,
 * regardless of track count or stream length. The bound must comfortably
 * cover one reorder window of aggregate stream data (see README).
 *
 * <p><b>Liveness contract.</b> All declared tracks should be produced
 * concurrently, each staying within one reorder window of the slowest track.
 * A frame arriving below the already-flushed watermark is rejected with a
 * {@link ValidationException}; a declared track that never produces causes
 * other producers to block once their queues fill (until
 * {@link #close()}/{@link #abort()} from another thread).
 */
public final class Muxer implements Closeable {

    private final Path output;
    private final List<TrackSpec> tracks; // sorted by trackId
    private final Map<Integer, TrackSpec> tracksById;
    private final MuxerConfig config;

    private final Object lock = new Object();
    /** Per-track reorder queues, each sorted by pts (unique within a track). */
    private final Map<Integer, PriorityQueue<BufferedFrame>> queues = new HashMap<>();
    /** Total bytes buffered across all queues; compared against the bound. */
    private long bufferedBytes;
    private final Map<Integer, Long> maxPtsMicrosSeen = new HashMap<>();
    /** Tracks whose producers signalled end-of-stream via {@link #endOfTrack}. */
    private final Set<Integer> endedTracks = new HashSet<>();
    private final Map<Integer, Set<Long>> seenPts = new HashMap<>();
    private long flushedWatermarkMicros = Long.MIN_VALUE;

    private DataOutputStream out;
    private CountingOutputStream countingOut;
    private boolean closed;
    private boolean aborted;

    private final List<List<long[]>> keyframeIndex; // parallel to sorted tracks
    private final long[] trackEndPts;               // in track timescale units
    private final long[] trackFrames;

    private record BufferedFrame(Frame frame, long ptsMicros) {
    }

    public Muxer(Path output, List<TrackSpec> tracks, MuxerConfig config) {
        TrackValidator.validateTracks(tracks);
        if (config.maxBufferedBytes() < 1024) {
            throw new IllegalArgumentException("maxBufferedBytes must be >= 1024");
        }
        this.output = output;
        this.tracks = ContainerFormat.sortedTracks(tracks);
        this.tracksById = new HashMap<>();
        for (TrackSpec t : this.tracks) {
            tracksById.put(t.trackId(), t);
            queues.put(t.trackId(), new PriorityQueue<>(
                    java.util.Comparator.comparingLong(BufferedFrame::ptsMicros)));
        }
        this.config = config;
        this.keyframeIndex = new ArrayList<>();
        for (int i = 0; i < this.tracks.size(); i++) {
            keyframeIndex.add(new ArrayList<>());
        }
        this.trackEndPts = new long[this.tracks.size()];
        java.util.Arrays.fill(trackEndPts, -1);
        this.trackFrames = new long[this.tracks.size()];
        try {
            this.countingOut = new CountingOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(output), 1 << 16));
            this.out = new DataOutputStream(countingOut);
            ContainerFormat.writeHeader(out, this.tracks);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to open " + output, e);
        }
    }

    /**
     * Submit one frame. Thread-safe; applies back-pressure when the track's
     * queue exceeds its share of the configured memory bound.
     *
     * @throws ValidationException   if the frame violates its track spec,
     *                               duplicates a pts on its track, or arrives
     *                               below the already-flushed watermark
     * @throws IllegalStateException if the muxer is closed or aborted
     */
    public void writeFrame(Frame frame) {
        TrackSpec spec = tracksById.get(frame.trackId());
        if (spec == null) {
            throw new ValidationException(frame.trackId(), "trackId", "no such track in container");
        }
        TrackValidator.validateFrame(spec, frame);
        long ptsMicros = ContainerFormat.toMicros(frame.pts(), spec.timescale());
        synchronized (lock) {
            if (closed || aborted) {
                throw new IllegalStateException("muxer already " + (closed ? "closed" : "aborted"));
            }
            if (ptsMicros < flushedWatermarkMicros) {
                throw new ValidationException(frame.trackId(), "pts",
                        "frame arrived too late: pts " + ptsMicros + "us is below the already "
                                + "written watermark " + flushedWatermarkMicros + "us; "
                                + "increase MuxerConfig.reorderWindowPts");
            }
            if (endedTracks.contains(frame.trackId())) {
                throw new ValidationException(frame.trackId(), "pts",
                        "frame submitted after endOfTrack for track " + frame.trackId());
            }
            Set<Long> ptsSet = seenPts.computeIfAbsent(frame.trackId(), k -> new HashSet<>());
            if (!ptsSet.add(frame.pts())) {
                throw new ValidationException(frame.trackId(), "pts",
                        "duplicate pts " + frame.pts() + " on track " + frame.trackId());
            }
            maxPtsMicrosSeen.merge(frame.trackId(), ptsMicros, Math::max);
            queues.get(frame.trackId()).add(new BufferedFrame(frame, ptsMicros));
            bufferedBytes += frame.payload().length + 64L;
            drain(false);
            // Back-pressure: block while the shared buffer is over the
            // configured bound, unless this track is the frontier (slowest)
            // track — the frontier producer must always progress so that
            // every other track's queue can drain.
            while (bufferedBytes > config.maxBufferedBytes()
                    && !isFrontier(frame.trackId())
                    && !closed && !aborted) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while waiting for muxer buffer", e);
                }
            }
            if (closed || aborted) {
                throw new IllegalStateException("muxer was " + (closed ? "closed" : "aborted")
                        + " while waiting for buffer space");
            }
        }
    }

    /**
     * Signal that a track will receive no more frames. Completed tracks stop
     * anchoring the reorder frontier, which lets the remaining tracks drain.
     * Every producer must call this (or {@link #close()}) when done.
     */
    public void endOfTrack(int trackId) {
        if (!tracksById.containsKey(trackId)) {
            throw new ValidationException(trackId, "trackId", "no such track in container");
        }
        synchronized (lock) {
            if (closed || aborted) {
                return;
            }
            endedTracks.add(trackId);
            drain(false);
        }
    }

    /** True when {@code trackId} is (tied for) the slowest active track. */
    private boolean isFrontier(int trackId) {
        long mine = maxPtsMicrosSeen.getOrDefault(trackId, Long.MIN_VALUE);
        for (Map.Entry<Integer, Long> e : maxPtsMicrosSeen.entrySet()) {
            if (!endedTracks.contains(e.getKey()) && e.getValue() < mine) {
                return false;
            }
        }
        return true;
    }

    /**
     * Emit every queued frame that can no longer be overtaken: pts at most
     * (min over started tracks of max pts seen) - reorder window. Frames are
     * emitted in globally sorted (pts, trackId) order. With {@code flushAll}
     * (on close/abort) everything remaining is emitted in the same order.
     */
    private void drain(boolean flushAll) {
        long frontier = Long.MAX_VALUE;
        if (!flushAll) {
            boolean anyActive = false;
            for (TrackSpec t : tracks) {
                int id = t.trackId();
                if (endedTracks.contains(id)) {
                    continue; // completed tracks no longer anchor the frontier
                }
                Long max = maxPtsMicrosSeen.get(id);
                if (max == null) {
                    // a declared track has not started and not ended: its
                    // first frame may still arrive at pts 0, so nothing is
                    // safe to flush yet
                    return;
                }
                frontier = Math.min(frontier, max);
                anyActive = true;
            }
            if (anyActive) {
                frontier -= config.reorderWindowPts();
            }
            // else: every declared track has ended -> frontier stays
            // MAX_VALUE, which flushes everything still queued
        }
        while (true) {
            // pick the smallest (ptsMicros, trackId) among all queue heads
            BufferedFrame best = null;
            int bestTrack = -1;
            for (Map.Entry<Integer, PriorityQueue<BufferedFrame>> e : queues.entrySet()) {
                BufferedFrame head = e.getValue().peek();
                if (head == null) {
                    continue;
                }
                if (best == null
                        || head.ptsMicros < best.ptsMicros
                        || (head.ptsMicros == best.ptsMicros
                            && head.frame.trackId() < best.frame.trackId())) {
                    best = head;
                    bestTrack = e.getKey();
                }
            }
            if (best == null || (!flushAll && best.ptsMicros > frontier)) {
                break;
            }
            queues.get(bestTrack).poll();
            bufferedBytes -= best.frame.payload().length + 64L;
            writeChunk(best.frame);
            flushedWatermarkMicros = Math.max(flushedWatermarkMicros, best.ptsMicros);
        }
        lock.notifyAll();
    }

    private void writeChunk(Frame frame) {
        try {
            int trackPos = trackPosition(frame.trackId());
            if (frame.keyFrame()) {
                keyframeIndex.get(trackPos).add(new long[]{frame.pts(), countingOut.count});
            }
            ContainerFormat.writeChunk(out, frame);
            long end = frame.pts() + frame.duration();
            if (end > trackEndPts[trackPos]) {
                trackEndPts[trackPos] = end;
            }
            trackFrames[trackPos]++;
        } catch (IOException e) {
            throw new UncheckedIOException("failed writing chunk to " + output, e);
        }
    }

    private int trackPosition(int trackId) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).trackId() == trackId) {
                return i;
            }
        }
        throw new IllegalStateException("unknown track " + trackId);
    }

    /** Finish the container: flush everything, write index footer and trailer. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed || aborted) {
                return;
            }
            drain(true);
            try {
                long footerOffset = countingOut.count;
                ContainerFormat.writeFooter(out, tracks, keyframeIndex, trackEndPts, trackFrames);
                ContainerFormat.writeTrailer(out, footerOffset, true);
                out.flush();
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("failed finishing " + output, e);
            }
            closed = true;
            lock.notifyAll();
        }
    }

    /**
     * Cancel writing. Buffered frames are flushed in deterministic order so
     * the largest possible valid prefix survives, but no footer/trailer is
     * written: a reader deterministically reports
     * {@link ContainerStatus#INCOMPLETE}. Rewriting only requires creating a
     * new Muxer on a fresh path; nothing is read back from the partial file.
     */
    public void abort() {
        synchronized (lock) {
            if (closed || aborted) {
                return;
            }
            try {
                drain(true);
                out.flush();
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("failed aborting " + output, e);
            }
            aborted = true;
            lock.notifyAll();
        }
    }

    public Path output() {
        return output;
    }

    /** Output stream that tracks how many bytes were written (chunk offsets). */
    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private long count;

        CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

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
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
