package com.ben.karmaasistan

import android.media.MediaPlayer
import java.io.File

/** Plays back the last take so the speaker can check it before saving. */
class TakePlayer {
    private var player: MediaPlayer? = null

    val isPlaying: Boolean get() = player?.isPlaying == true

    /** Playback position, 0..1. */
    val progress: Float
        get() = player?.let { if (it.duration > 0) it.currentPosition.toFloat() / it.duration else 0f } ?: 0f

    fun play(file: File) {
        release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
    }

    fun stop() {
        player?.run { if (isPlaying) stop() }
        release()
    }

    fun release() {
        player?.release()
        player = null
    }
}
