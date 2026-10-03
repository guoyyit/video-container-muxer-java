# Container Format ("MUX1")

A simple, deterministic binary container for in-process muxing. All integers
are big-endian. The file is written by a single writer thread; output bytes
depend only on the input frames, never on thread scheduling or enqueue order.

## Layout

```
[magic 4][header block][frame blocks...][index block][trailer 29]
```

### Magic
`"MUX1"` (4 bytes, US-ASCII). Present at file offset 0 and in the trailer.

### Header block

| offset | size | field |
|--------|------|-------|
| 0 | 4 | magic `"MUX1"` |
| 4 | 2 | version (u16, currently 1) |
| 6 | 2 | flags (u16, reserved, 0) |
| 8 | 4 | headerLength (u32, bytes after magic) |
| 12 | 2 | trackCount (u16) |
| 14 | ... | track entries (repeated trackCount) |

Each track entry:

| size | field |
|------|-------|
| 4 | trackId (u32) |
| 1 | trackType (u8: 1=VIDEO, 2=AUDIO) |
| 1 | codecLen (u8) |
| codecLen | codec (US-ASCII) |
| 4 | timescale (u32, ticks per second) |
| 8 | durationTicks (u64) |
| 8 | frameCount (u64) |
| 8 | keyframeCount (u64) |
| 4 | reorderWindowTicks (u32) |
| 4 | presentationReorderTicks (u32) |
| 4 | codecPrivateLen (u32) |
| codecPrivateLen | codecPrivate (bytes) |
| video: 4+4 | width (u32), height (u32) |
| audio: 4+1+1 | sampleRate (u32), channels (u8), bitsPerSample (u8) |

The header is written twice: once at start (with zero counts) and once at
finalize (with real counts/durations, same byte length).

### Frame blocks

| size | field |
|------|-------|
| 4 | trackId (u32) |
| 8 | pts (u64, presentation timestamp) |
| 8 | dts (u64, decode timestamp) |
| 4 | duration (u32, in track ticks) |
| 1 | flags (u8: bit0=keyframe) |
| 4 | dataSize (u32) |
| dataSize | codec payload |

Frames are interleaved across tracks in DTS order (decode order). Within a
track, DTS is non-decreasing.

### Index block

| size | field |
|------|-------|
| 4 | entryCount (u32) |
| entryCount * 33 | index entries |

Each index entry (33 bytes):

| size | field |
|------|-------|
| 4 | trackId (u32) |
| 8 | pts (u64) |
| 8 | dts (u64) |
| 8 | offset (u64, absolute file offset of the frame block) |
| 4 | blockSize (u32, total block bytes) |
| 1 | flags (u8) |

The index is sorted by (trackId, pts). Only keyframes are indexed.

### Trailer (29 bytes)

| size | field |
|------|-------|
| 4 | magic `"MUX1"` |
| 8 | indexOffset (u64) |
| 4 | indexLength (u32) |
| 8 | dataEndOffset (u64, offset where the index begins = end of frames) |
| 1 | complete (u8: 1 = finalized) |
| 4 | crc32 (u32, CRC32 over every byte before the trailer) |

## Incomplete / corrupt detection

- **Incomplete**: trailer magic missing, `complete != 1`, or file too short.
  Thrown as `ContainerIncompleteException`.
- **Corrupt**: CRC mismatch, bad magic/version, index/frame mismatch,
  truncated block. Thrown as `ContainerCorruptException`.

A cancelled write leaves the file without a trailer; reopening it always fails
with `ContainerIncompleteException`, and repeated opens fail stably. A rewrite
truncates the file and never reads the old tail.
