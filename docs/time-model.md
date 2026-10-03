# Time Model

## Timestamps

Each frame carries two timestamps, both in the track's timescale (ticks per
second, e.g. 1_000_000 for microseconds):

- **PTS (Presentation Time Stamp)**: when the frame should be presented.
- **DTS (Decode Time Stamp)**: when the frame should be decoded.

For simple codecs (and all audio), PTS == DTS. For B-frames, the decode order
differs from the presentation order: the reference I/P frame is decoded before
the B-frames but presented after them, so DTS > PTS for B-frames.

## Ordering guarantees

- **Decode order (DTS)**: frames are stored in DTS order, interleaved across
  tracks. Within a track, DTS is non-decreasing.
- **Presentation order (PTS)**: the reader presents frames sorted by PTS. PTS
  is strictly increasing in presentation order (no backward flow, no
  duplicates).
- **Intervals**: the presentation interval between consecutive frames equals
  the input; the muxer never alters timestamps.

## Reorder windows

Two bounds govern how much reordering the muxer/reader must tolerate:

- **reorderWindowTicks** (arrival reorder): frames may arrive out of order
  within this window. The muxer delays writing a frame until every track has
  enqueued a frame with DTS >= candidate DTS + window, guaranteeing a
  deterministic interleave while bounding memory. Default: 0 (in-order
  arrival).
- **presentationReorderTicks** (presentation reorder): the maximum
  `DTS - PTS` delta (B-frame depth). The reader buffers at most this many
  ticks of frames before emitting in PTS order. Default: 1 second of ticks.

## Duration

- **Track duration** = `max(pts + duration)` over all frames (the end of the
  last frame). If all durations are 0, the track duration is the last PTS.
- **Container duration** = max over tracks of track durations (converted to
  microseconds).

When audio and video end at different times, the container duration is the
longer of the two. Both per-track durations and the container duration are
exposed, so the misalignment is explicit.

## Timescale conversion

`durationUs = durationTicks * 1_000_000 / timescale` (integer division,
rounded down).
