package com.libvio.tv.playback

/** One native and at most one software attempt per source in an automatic recovery chain. */
internal class PlaybackAttempts {
    private val native = mutableSetOf<Int>()
    private val software = mutableSetOf<Int>()
    fun native(source: Int) { native += source }
    fun software(source: Int): Boolean = source in native && software.add(source)
    fun tried(source: Int) = source in native
    fun clear() { native.clear(); software.clear() }
}

/** A detached video output is not proof that the episode finished. */
internal fun isCompletePlayback(positionMs: Long, durationMs: Long): Boolean =
    durationMs > 0 && positionMs >= durationMs - minOf(2_000, durationMs / 20)
