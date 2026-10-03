# Seek Semantics

Random access is keyframe-based and deterministic: opening the same file and
seeking to the same PTS always yields the same result.

## Keyframe index

Only keyframes (sync samples) are indexed. For video, keyframes are marked by
the producer. For audio, every sample is treated as a keyframe (audio packets
are independently decodable).

## Seek algorithm

`seek(trackId, requestedPts)` returns a `SeekOutcome`:

1. **NO_KEYFRAME** — the track has no keyframes, so random access is
   impossible. Sequential reading still works.
2. **BEFORE_FIRST_KEYFRAME** — `requestedPts < firstKeyframePts`. There is no
   keyframe at or before the request, so decoding cannot resume from a
   valid point.
3. **BEYOND_END** — `requestedPts >= trackDurationTicks`. The request is at or
   beyond the end of the track.
4. **HIT** — resumed from the keyframe with the largest PTS <= requestedPts.
   `resumedPts` may be less than `requestedPts` (the request fell in a gap
   between keyframes); decode resumes from the keyframe and presents forward.

## Gap times

A request that falls between two keyframes (or between two frames) snaps to
the earlier keyframe. `resumedPts` is the keyframe's PTS, which is <= the
requested PTS. Use `framesFrom(trackId, pts)` to iterate from the resume
point in presentation order.

## Resume and presentation order

`framesFrom(trackId, requestedPts)` seeks to the keyframe and returns a
streaming iterator over frames from that offset in PTS order. The first
returned frame is the keyframe. For B-frame content, a frame decoded after the
keyframe but presented before it may be returned first (its PTS is less than
the keyframe's PTS); callers that need to start exactly at or after the
requested PTS should skip frames with PTS < requestedPts.

## Determinism

The index is sorted by (trackId, PTS), and seek uses binary search. The same
file + same request always produces the identical outcome.
