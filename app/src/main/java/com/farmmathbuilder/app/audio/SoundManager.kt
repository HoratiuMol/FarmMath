package com.farmmathbuilder.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

/**
 * Thin wrapper around SoundPool (short one-shot SFX, e.g. navigation clicks) and
 * MediaPlayer (a single looping ambient track). Sounds are looked up by raw-resource
 * *name* via `resources.getIdentifier` rather than generated `R.raw.*` references, so
 * the app keeps compiling/running cleanly while assets are added incrementally to
 * `res/raw/` — a missing name is silently skipped instead of a build/runtime failure.
 *
 * A process-wide singleton (not tied to a Composable's lifecycle) since sound should
 * keep playing correctly across recompositions/navigation.
 */
object SoundManager {

    /** Canonical raw-resource names this app expects — drop matching files (.ogg/.wav)
     * into res/raw/ to activate each sound; nothing else needs to change. */
    object Sounds {
        const val CLICK = "sfx_click"
        const val BACK = "sfx_back"
        const val DIALOG_OPEN = "sfx_dialog_open"
        const val DIALOG_CLOSE = "sfx_dialog_close"
        const val PLANT = "sfx_plant"
        const val HARVEST = "sfx_harvest"
        const val CANCEL_GROWTH = "sfx_cancel_growth"
        const val AMBIENT_FARM = "ambient_farm_loop"
    }

    private const val MAX_STREAMS = 4

    private var soundPool: SoundPool? = null
    private var ambientPlayer: MediaPlayer? = null
    private val loadedSoundIds = mutableMapOf<String, Int>()

    private var appContext: Context? = null
    private var sfxVolume = 0.7f
    private var musicVolume = 0.7f
    private var muted = false

    private var ambientTrackName: String? = null
    private var ambientShouldBePlaying = false

    fun init(context: Context) {
        if (soundPool != null) return
        appContext = context.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(attrs)
            .build()
    }

    /** Call whenever the persisted Settings (volume sliders / mute switch) change. */
    fun updateSettings(sfxVolume: Float, musicVolume: Float, muted: Boolean) {
        val wasMuted = this.muted
        this.sfxVolume = sfxVolume
        this.musicVolume = musicVolume
        this.muted = muted

        if (muted && !wasMuted) {
            ambientPlayer?.pause()
        } else if (!muted && wasMuted && ambientShouldBePlaying) {
            ambientTrackName?.let { playAmbient(it) }
        }
        ambientPlayer?.setVolume(effectiveMusicVolume(), effectiveMusicVolume())
    }

    private fun effectiveMusicVolume() = if (muted) 0f else musicVolume

    private fun rawResId(name: String): Int? {
        val ctx = appContext ?: return null
        val id = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
        return if (id != 0) id else null
    }

    /** Plays a short one-shot sound (see [Sounds] for the expected names). No-op if
     * muted, SoundManager isn't initialized yet, or the asset hasn't been added. */
    fun playSfx(name: String) {
        if (muted) return
        val pool = soundPool ?: return
        val id = loadedSoundIds.getOrPut(name) {
            rawResId(name)?.let { pool.load(appContext, it, 1) } ?: -1
        }
        if (id == -1) return
        pool.play(id, sfxVolume, sfxVolume, 1, 0, 1f)
    }

    /** Starts (or resumes) the single looping ambient track. Safe to call repeatedly —
     * only (re)creates the player the first time or after [release]. */
    fun playAmbient(name: String) {
        ambientTrackName = name
        ambientShouldBePlaying = true
        if (muted) return
        val ctx = appContext ?: return
        if (ambientPlayer == null) {
            val id = rawResId(name) ?: return
            ambientPlayer = MediaPlayer.create(ctx, id)?.apply {
                isLooping = true
                setVolume(effectiveMusicVolume(), effectiveMusicVolume())
            }
        }
        ambientPlayer?.start()
    }

    /** Call from Activity.onPause/onStop so the ambient loop doesn't keep playing
     * while the app is backgrounded. */
    fun pauseAmbient() {
        ambientShouldBePlaying = false
        ambientPlayer?.pause()
    }

    /** Call from Activity.onResume to resume whatever track was last playing. */
    fun resumeAmbient() {
        val name = ambientTrackName ?: return
        playAmbient(name)
    }

    /** Call from Activity.onDestroy to free native audio resources. */
    fun release() {
        soundPool?.release()
        soundPool = null
        loadedSoundIds.clear()
        ambientPlayer?.release()
        ambientPlayer = null
        ambientTrackName = null
        ambientShouldBePlaying = false
    }
}
