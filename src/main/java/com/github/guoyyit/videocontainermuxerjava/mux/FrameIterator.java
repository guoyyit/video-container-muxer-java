package com.github.guoyyit.videocontainermuxerjava.mux;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Streams frames of one track in presentation (PTS) order from a byte offset.
 *
 * <p>Blocks are stored in decode (DTS) order and read sequentially (no backward
 * seeks). A bounded min-heap of buffered frames is emitted from when the next
 * stored block's DTS exceeds the smallest buffered PTS plus the presentation
 * reorder bound (or at end of stream), which reorders B-frames with O(reorder
 * distance) memory.
 */
final class FrameIterator implements Iterator<Frame>, AutoCloseable {

    private static final int MAX_BUFFER = 4096;

    private final RandomAccessFile raf;
    private final long endOffset;
    private final int trackId;
    private final long presentationReorderTicks;

    private final PriorityQueue<Frame> minHeap;

    private long pos;
    private Block peek;
    private boolean eos;
    private Frame emitted;

    private record Block(int trackId, long pts, long dts, long duration, boolean keyframe, byte[] data) {
    }

    FrameIterator(java.nio.file.Path path, long startOffset, long endOffset, int trackId,
                  long presentationReorderTicks) throws IOException {
        this.raf = new RandomAccessFile(path.toFile(), "r");
        this.endOffset = endOffset;
        this.trackId = trackId;
        this.presentationReorderTicks = presentationReorderTicks;
        this.pos = startOffset;
        this.minHeap = new PriorityQueue<>(Comparator.comparingLong(Frame::pts)
                .thenComparingLong(Frame::dts));
    }

    @Override
    public boolean hasNext() {
        while (emitted == null) {
            // ensure we have the next block buffered for the emit decision
            if (peek == null && !eos) {
                if (pos >= endOffset) {
                    eos = true;
                } else {
                    peek = readBlock();
                }
            }
            if (!minHeap.isEmpty()) {
                Long nextDts = eos ? null : peek.dts;
                long minPts = minHeap.peek().pts();
                if (nextDts == null || nextDts > minPts + presentationReorderTicks
                        || minHeap.size() >= MAX_BUFFER) {
                    emitted = minHeap.poll();
                    return true;
                }
            }
            if (eos) {
                if (!minHeap.isEmpty()) {
                    emitted = minHeap.poll();
                    return true;
                }
                return false;
            }
            // consume the peeked block
            if (peek.trackId == trackId) {
                minHeap.add(new Frame(peek.pts, peek.dts, peek.duration, peek.keyframe, peek.data));
            }
            peek = null;
        }
        return true;
    }

    @Override
    public Frame next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        Frame f = emitted;
        emitted = null;
        return f;
    }

    private Block readBlock() {
        try {
            raf.seek(pos);
            int trackId = raf.readInt();
            long pts = raf.readLong();
            long dts = raf.readLong();
            long duration = raf.readInt() & 0xFFFFFFFFL;
            boolean keyframe = (raf.readUnsignedByte() & ContainerFormat.FLAG_KEYFRAME) != 0;
            int dataSize = raf.readInt();
            if (dataSize < 0 || pos + ContainerFormat.BLOCK_HEADER_SIZE + dataSize > endOffset) {
                throw new ContainerCorruptException("truncated frame block at offset " + pos);
            }
            byte[] data = new byte[dataSize];
            raf.readFully(data);
            pos += ContainerFormat.BLOCK_HEADER_SIZE + dataSize;
            return new Block(trackId, pts, dts, duration, keyframe, data);
        } catch (IOException e) {
            throw new ContainerCorruptException("failed to read frame block at " + pos, e);
        }
    }

    @Override
    public void close() {
        try {
            raf.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
