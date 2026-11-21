package com.vrgames.fishing.render

import android.content.Context
import android.media.MediaPlayer

/**
 * v0.5: the tutorial's recorded lines (assets/narration/<id>.ogg, ListenHub, tools/narration/clips.tsv). One at a time:
 * a new line stops the last (the player is always told about what just happened). Main thread.
 */
class Voice(private val context: Context) {
    private var player: MediaPlayer? = null
    /** The line playing (or last played), for the log. */
    var current: String? = null; private set

    fun play(id: String) {
        stop()
        current = id
        runCatching {
            context.assets.openFd("narration/$id.ogg").use { fd ->
                player = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { done ->
                        val mine = player === done
                        done.release()
                        if (mine) player = null
                    }
                    prepare()
                    start()
                }
            }
            android.util.Log.i("FishVoice", "playing $id")
        }.onFailure { android.util.Log.w("FishVoice", "$id: ${it.message}") }
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }
}
