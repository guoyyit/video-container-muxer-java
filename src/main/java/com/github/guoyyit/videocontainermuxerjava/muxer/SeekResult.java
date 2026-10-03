package com.github.guoyyit.videocontainermuxerjava.muxer;

/**
 * Deterministic result of a seek.
 *
 * @param status      classification of the outcome
 * @param keyframePts pts of the keyframe decoding must restart from,
 *                    or -1 when no keyframe is available
 * @param targetPts   pts of the first frame at or after the requested time,
 *                    or -1 when unknown/not applicable
 */
public record SeekResult(SeekStatus status, long keyframePts, long targetPts) {

    public boolean ok() {
        return status == SeekStatus.EXACT || status == SeekStatus.IN_GAP;
    }
}
