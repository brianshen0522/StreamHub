package com.streamhub.tv.sfx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What the focus engine (and screens) play sounds through. Every call is fire-and-forget. */
interface UiSounds {
    /** `sfx::play`. Returns whether the sound was accepted. */
    fun play(sound: Sound): Boolean

    /** `sfx::play_repeat`: a held key's move, spaced wider (120 ms) and at 60 %. */
    fun playRepeat(sound: Sound): Boolean

    /** Silence: tests, previews, and the screenshot runs. */
    object None : UiSounds {
        override fun play(sound: Sound) = false
        override fun playRepeat(sound: Sound) = false
    }
}

/** [UiSounds] plus the switches: the 音效 toggle, player silence and backgrounding. */
interface SfxControl : UiSounds {
    /** `enabled()`. */
    val enabled: Boolean

    /** `setEnabled`: persisted; turning off silences what is sounding. Returns the new state. */
    fun setEnabled(on: Boolean): Boolean

    /** `setPlayer`: true while the player is up (the shell calls this for you). */
    fun setPlayer(up: Boolean)

    /** The activity stopped (true) or started (false). */
    fun setHidden(hidden: Boolean)

    /** Always silent; remembers the toggle in memory only. */
    class Silent(override var enabled: Boolean = true) : SfxControl {
        override fun play(sound: Sound) = false
        override fun playRepeat(sound: Sound) = false
        override fun setEnabled(on: Boolean): Boolean {
            enabled = on
            return on
        }
        override fun setPlayer(up: Boolean) = Unit
        override fun setHidden(hidden: Boolean) = Unit
    }
}

/**
 * The interface sounds (`tizen/app/js/sfx.js`): synthesised at the device's
 * native rate, decided by [SfxRules], and rendered by one low-latency
 * streaming [AudioTrack] fed from a small mixer thread that plays the current
 * voice and fades out the cut ones over 8 ms. SoundPool cannot fade or apply
 * the priority rules, so it is not used.
 *
 * - Off/on persists in SharedPreferences `streamhub` key `sfx` ("0" = off,
 *   missing = on), the same semantics as the web storage key `streamhub.sfx`.
 * - [setPlayer] silences everything while the player is up and suspends the
 *   output once the OK that opened it has finished its pop (+30 ms).
 * - [setHidden] (the activity stopped) silences and suspends; visible again
 *   (and not in the player) resumes.
 * - Never throws. A device with no output simply stays silent.
 *
 * Call from the main thread.
 */
class TvSfx(context: Context) : SfxControl {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val clock: () -> Long = { SystemClock.uptimeMillis() }

    private val rate: Int = runCatching {
        AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    }.getOrDefault(48_000).takeIf { it > 0 } ?: 48_000

    private val rules = SfxRules(clock) { SfxSynth.durationMs(rate, it.synth) }
    private var buffers: Map<Sound, ShortArray>? = null
    private var mixer: Mixer? = null
    private var mixerFailed = false

    init {
        rules.enabled = readEnabled()
    }

    override val enabled: Boolean get() = rules.enabled

    override fun play(sound: Sound): Boolean = offer(sound, false)

    override fun playRepeat(sound: Sound): Boolean = offer(sound, true)

    private fun offer(sound: Sound, repeat: Boolean): Boolean = try {
        val output = when {
            !rules.enabled || rules.playerUp || rules.hidden -> SfxOutput.Running // decided before output matters
            else -> ensureMixer()?.state ?: SfxOutput.None
        }
        when (val d = rules.offer(sound, repeat, output)) {
            is SfxDecision.Play -> {
                mixer?.start(buffers!!.getValue(d.sound), d.gain)
                true
            }
            is SfxDecision.Drop -> {
                if (d.reason == SfxDecision.Reason.Suspended) mixer?.resume()
                false
            }
        }
    } catch (_: Throwable) {
        false
    }

    /** `setEnabled`: persists, and turning off silences what is sounding. */
    override fun setEnabled(on: Boolean): Boolean {
        rules.enabled = on
        runCatching { prefs.edit().putString(KEY, if (on) "1" else "0").apply() }
        if (!on) silence()
        return on
    }

    /** `setPlayer`: silent while the player is up; the output is freed once the current voice ends. */
    override fun setPlayer(up: Boolean) {
        try {
            rules.playerUp = up
            val m = mixer ?: return
            if (up) {
                val left = rules.remainingMs()
                main.postDelayed({ if (rules.playerUp) m.suspend() }, left + 30)
            } else if (!rules.hidden) {
                m.resume()
            }
        } catch (_: Throwable) {
        }
    }

    /** The app went to the background (true) or came back (false): `visibilitychange`. */
    override fun setHidden(hidden: Boolean) {
        try {
            rules.hidden = hidden
            val m = mixer ?: return
            if (hidden) {
                silence()
                m.suspend()
            } else if (!rules.playerUp) {
                m.resume()
            }
        } catch (_: Throwable) {
        }
    }

    /** Release the output for good (activity destroyed). */
    fun release() {
        runCatching { mixer?.shutdown() }
        mixer = null
    }

    private fun silence() {
        rules.silence()
        mixer?.cut()
    }

    private fun readEnabled(): Boolean = runCatching { prefs.getString(KEY, null) != "0" }.getOrDefault(true)

    private fun ensureMixer(): Mixer? {
        mixer?.let { return it }
        if (mixerFailed) return null
        return try {
            buffers = Sound.entries.associateWith { s ->
                val f = SfxSynth.render(rate, s.synth)
                ShortArray(f.size) { i -> (f[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
            }
            Mixer(rate).also { mixer = it }
        } catch (_: Throwable) {
            mixerFailed = true
            null
        }
    }

    /**
     * One streaming AudioTrack and the thread that feeds it. The thread renders
     * small blocks: the current voice at its gain, plus any voices being cut,
     * each ramping linearly to 0 over 8 ms. While running and idle it writes
     * silence, as a running AudioContext does; suspended, it pauses the track
     * and sleeps.
     */
    private class Mixer(private val rate: Int) {
        private class Voice(val data: ShortArray, val gain: Float) {
            var pos = 0
            var fadeLeft = -1 // frames; -1 = not fading
        }

        private val lock = ReentrantLock()
        private val wake = lock.newCondition()
        private var current: Voice? = null
        private val fading = ArrayList<Voice>(4)
        private var suspended = false
        private var alive = true
        private val fadeFrames = (rate * SFX_FADE_MS / 1000.0).toInt().coerceAtLeast(1)
        private val block = (rate / 1000 * 2).coerceIn(64, 256) // ~2 ms
        private val track: AudioTrack = buildTrack()
        private val thread = Thread(::loop, "streamhub-sfx").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
        }

        @Volatile
        var state: SfxOutput = SfxOutput.Running
            private set

        init {
            track.play()
            thread.start()
        }

        private fun buildTrack(): AudioTrack {
            val format = AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val builder = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(if (min > 0) min else block * 2 * 4)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            return builder.build()
        }

        fun start(data: ShortArray, gain: Float) {
            lock.withLock {
                current?.let { it.fadeLeft = fadeFrames; fading.add(it) }
                current = Voice(data, gain)
                wake.signalAll()
            }
        }

        fun cut() {
            lock.withLock {
                current?.let { it.fadeLeft = fadeFrames; fading.add(it) }
                current = null
            }
        }

        fun suspend() {
            lock.withLock {
                if (suspended || !alive) return@withLock
                suspended = true
                state = SfxOutput.Suspended
                current = null
                fading.clear()
                runCatching { track.pause(); track.flush() }
            }
        }

        fun resume() {
            lock.withLock {
                if (!suspended || !alive) return@withLock
                suspended = false
                state = SfxOutput.Running
                runCatching { track.play() }
                wake.signalAll()
            }
        }

        fun shutdown() {
            lock.withLock {
                alive = false
                wake.signalAll()
            }
            runCatching { thread.join(200) }
            runCatching { track.release() }
        }

        private fun loop() {
            val mix = FloatArray(block)
            val out = ShortArray(block)
            while (true) {
                lock.withLock {
                    while (alive && suspended) wake.await()
                    if (!alive) return
                    java.util.Arrays.fill(mix, 0f)
                    current?.let { v ->
                        var i = 0
                        while (i < block && v.pos < v.data.size) {
                            mix[i] += v.data[v.pos] * v.gain
                            v.pos++
                            i++
                        }
                        if (v.pos >= v.data.size) current = null
                    }
                    val it = fading.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        var i = 0
                        while (i < block && v.pos < v.data.size && v.fadeLeft > 0) {
                            mix[i] += v.data[v.pos] * v.gain * (v.fadeLeft.toFloat() / fadeFrames)
                            v.pos++
                            v.fadeLeft--
                            i++
                        }
                        if (v.fadeLeft <= 0 || v.pos >= v.data.size) it.remove()
                    }
                }
                for (i in 0 until block) out[i] = mix[i].toInt().coerceIn(-32768, 32767).toShort()
                val written = runCatching { track.write(out, 0, block) }.getOrDefault(-1)
                if (written < 0) {
                    lock.withLock {
                        state = SfxOutput.None
                        alive = false
                    }
                    return
                }
            }
        }
    }

    companion object {
        const val PREFS = "streamhub"
        const val KEY = "sfx"
    }
}
