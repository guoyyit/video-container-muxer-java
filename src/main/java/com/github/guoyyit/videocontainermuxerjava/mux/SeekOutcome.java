package com.github.guoyyit.videocontainermuxerjava.mux;

/**
 * The result of seeking a track to a presentation timestamp.
 *
 * @param status        the seek conclusion
 * @param trackId       the requested track
 * @param requestedPts  the requested presentation timestamp (track timescale)
 * @param resumedPts    the keyframe PTS actually resumed from (HIT only)
 * @param resumedDts    the keyframe DTS actually resumed from (HIT only)
 * @param offset        file offset of the keyframe block (HIT only)
 * @param blockSize     total size of the keyframe block in bytes (HIT only)
 */
public record SeekOutcome(SeekStatus status, int trackId, long requestedPts,
                          long resumedPts, long resumedDts, long offset, int blockSize) {

    public boolean isHit() { return status == SeekStatus.HIT; }

    static SeekOutcome hit(int trackId, long requestedPts, long resumedPts,
                           long resumedDts, long offset, int blockSize) {
        return new SeekOutcome(SeekStatus.HIT, trackId, requestedPts, resumedPts,
                resumedDts, offset, blockSize);
    }

    static SeekOutcome miss(SeekStatus status, int trackId, long requestedPts) {
        return new SeekOutcome(status, trackId, requestedPts, -1, -1, -1L, 0);
    }
}
