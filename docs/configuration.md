# Configuration

## MuxerConfig

| option | default | description |
|--------|---------|-------------|
| maxPendingBytes | 64 MiB | Global upper bound on in-flight frame payloads across all tracks. Producers block (backpressure) when exceeded. |
| maxPendingFramesPerTrack | 512 | Per-track cap on buffered frames (fairness, prevents one track from starving others). |
| maxFrameSize | 16 MiB | Maximum size of a single frame payload. Must be <= maxPendingBytes. |
| maxTracks | 64 | Maximum number of tracks. |

Memory usage is bounded by `maxPendingBytes` regardless of track count or
runtime. A slow or blocked producer only blocks its own enqueue (and the
writer when all tracks are waiting); it cannot misalign the interleaving or
grow memory without bound.

## TrackConfig

| option | default | description |
|--------|---------|-------------|
| trackId | auto | Explicit track id (0..65535). Duplicate ids are rejected. |
| type | — | VIDEO or AUDIO. |
| codec | — | Codec name (e.g. "AVC", "AAC"). |
| timescale | 1_000_000 | Ticks per second. Must be in (0, 1_000_000_000]. |
| reorderWindowTicks | 0 | Arrival reorder window. Frames may arrive out of order within this many ticks. |
| presentationReorderTicks | timescale (1s) | Max DTS-PTS delta for B-frame reorder. |
| codecPrivate | empty | Codec-specific bytes (e.g. SPS/PPS for AVC). |
| width/height | — | Video dimensions (required for VIDEO). |
| sampleRate/channels/bitsPerSample | — | Audio parameters (required for AUDIO). |

## Validation

Validation runs at two points:

- **Before write** (`addTrack`, `writeFrame`): track parameters and frame
  ranges are rejected with a `ContainerValidationException` carrying the
  track id and field name.
- **Before finalize** (frame section scan): DTS monotonicity, PTS
  non-duplication, presentation reorder bound, and counts are verified before
  the trailer is committed. A validation failure leaves the file incomplete.
- **On read** (`ContainerReader.open`): the trailer, CRC, header, and index
  are validated against the frame blocks.

## Tuning for large inputs

- Increase `maxPendingBytes` for higher throughput (more in-flight frames).
- Set `maxFrameSize` to the largest expected frame.
- The reader streams blocks; only the keyframe index is loaded into memory.
