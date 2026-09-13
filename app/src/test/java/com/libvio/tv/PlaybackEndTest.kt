package com.libvio.tv

import com.libvio.tv.playback.isCompletePlayback
import org.junit.Assert.*
import org.junit.Test

class PlaybackEndTest {
    @Test fun outputLossMidEpisodeIsNotCompletion() { assertFalse(isCompletePlayback(170_000, 3_187_000)) }
    @Test fun finalFrameAllowsSmallTimestampLag() { assertTrue(isCompletePlayback(3_186_500, 3_187_000)) }
    @Test fun unknownDurationDoesNotAdvanceEpisode() { assertFalse(isCompletePlayback(170_000, -1)) }
    @Test fun shortClipMustStillReachItsEnd() { assertFalse(isCompletePlayback(0, 1000)); assertTrue(isCompletePlayback(990, 1000)) }
}
