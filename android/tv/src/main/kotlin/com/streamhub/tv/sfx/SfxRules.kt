package com.streamhub.tv.sfx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/*
 * Interface sounds, a port of tizen/app/js/sfx.js (PARITY §8). This file is the
 * pure half — the synthesis formula and the rules that decide whether a sound
 * plays — with no Android types, so it is unit-tested on the JVM. TvSfx feeds
 * the decisions to one AudioTrack.
 */

/** `sfx.js` MASTER. */
const val SFX_MASTER = 0.5f
/** `sfx.js` FADE: a cut voice ramps to 0 over 8 ms… */
const val SFX_FADE_MS = 8.0
/** …and stops 2 ms after that. */
const val SFX_STOP_AFTER_FADE_MS = 2.0
/** Held moves play at 60 % (`sfx.js:149`). */
const val SFX_HELD_MOVE_GAIN = 0.6f

/** One partial of a sound: frequency multiple and amplitude. */
data class Partial(val mult: Double, val amp: Double)

/** `synth(rate, o)` parameters (`sfx.js:100-115`). */
data class SynthSpec(
    val dur: Double,
    val attack: Double,
    val decay: Double,
    val from: Double,
    val to: Double,
    val partials: List<Partial>,
)

/**
 * The four sounds with their `SPEC` (`sfx.js:31-36`) and synthesis
 * (`sfx.js:100-115`). [prio]: higher wins while it is still sounding. [gapMs]:
 * how long the same sound must wait; [holdGapMs] replaces it for a held repeat.
 */
enum class Sound(
    val key: String,
    val prio: Int,
    val gapMs: Long,
    val holdGapMs: Long?,
    val gain: Float,
    val synth: SynthSpec,
) {
    /** A tiny, high, rounded tick: focus moved. */
    Move("move", 1, 70, 120, 0.11f, SynthSpec(0.045, 0.002, 0.012, 1400.0, 1320.0, listOf(Partial(1.0, 1.0), Partial(2.0, 0.12)))),
    /** A low, short thud: nothing in that direction. */
    Edge("edge", 2, 260, null, 0.30f, SynthSpec(0.09, 0.003, 0.03, 190.0, 130.0, listOf(Partial(1.0, 1.0), Partial(2.0, 0.25)))),
    /** A soft rising pop: OK. */
    Select("select", 3, 140, null, 0.17f, SynthSpec(0.11, 0.003, 0.035, 700.0, 940.0, listOf(Partial(1.0, 1.0), Partial(1.5, 0.3), Partial(2.0, 0.12)))),
    /** The same pop, falling: Back. */
    Back("back", 3, 140, null, 0.15f, SynthSpec(0.11, 0.003, 0.035, 900.0, 620.0, listOf(Partial(1.0, 1.0), Partial(1.5, 0.25)))),
}

object SfxSynth {
    /** Samples in a sound's buffer: `Math.ceil(rate * dur)`. */
    fun length(rate: Int, spec: SynthSpec): Int = ceil(rate * spec.dur).toInt()

    /** The buffer's duration in ms, which is what `voice.end` adds. */
    fun durationMs(rate: Int, spec: SynthSpec): Double = length(rate, spec).toDouble() / rate * 1000.0

    /**
     * `synth(rate, o)`, sample for sample: a quadratic-ease pitch glide, a
     * linear attack, an exponential decay, the last 3 ms ramped to zero, and the
     * result normalised to a peak of 1 (level comes from the gains). Samples are
     * float32, as in the AudioBuffer, including the peak search.
     */
    fun render(rate: Int, spec: SynthSpec): FloatArray {
        val n = length(rate, spec)
        val d = FloatArray(n)
        val attack = max(1, floor(rate * spec.attack).toInt())
        val phase = DoubleArray(spec.partials.size)
        val tailN = floor(rate * 0.003).toInt()
        var peak = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val glide = spec.from + (spec.to - spec.from) * (1 - (1 - t).pow(2))
            var env = (if (i < attack) i.toDouble() / attack else 1.0) * exp(-(i.toDouble() / rate) / spec.decay)
            val tail = n - i
            if (tail < tailN) env *= tail.toDouble() / tailN
            var s = 0.0
            for (k in spec.partials.indices) {
                phase[k] += (2 * PI * glide * spec.partials[k].mult) / rate
                s += sin(phase[k]) * spec.partials[k].amp
            }
            d[i] = (s * env).toFloat()
            if (abs(d[i]) > peak) peak = abs(d[i])
        }
        if (peak > 0f) for (j in 0 until n) d[j] = d[j] / peak
        return d
    }
}

/** What [SfxRules.offer] decided. */
sealed interface SfxDecision {
    /** Start [sound] at [gain] (MASTER included); [cut] fades the sounding voice out over 8 ms first. */
    data class Play(val sound: Sound, val gain: Float, val cut: Boolean) : SfxDecision

    /** Not played. [counted] mirrors `counts.dropped` in sfx.js. */
    data class Drop(val reason: Reason, val counted: Boolean) : SfxDecision

    enum class Reason { Disabled, PlayerUp, Hidden, Gap, Priority, NoOutput, Suspended }
}

/** The audio output's state, as `ctx` / `ctx.state` in sfx.js. */
enum class SfxOutput { None, Running, Suspended }

/**
 * The decisions of `play(name, repeat)` (`sfx.js:137-170`), in the same order:
 * off / player up / hidden → silent; the same sound inside its gap → dropped; a
 * still-sounding voice of strictly higher priority → dropped; no output → silent;
 * a suspended output → resume it and drop this sound rather than play it late;
 * otherwise play, cutting a still-sounding voice. One voice, never layered.
 *
 * [durationMs] is each sound's buffer length in ms (it sets `voice.end`).
 * Not thread-safe: call from one thread.
 */
class SfxRules(
    private val clock: () -> Long,
    private val durationMs: (Sound) -> Double,
) {
    var enabled = true
    var playerUp = false
    var hidden = false

    private data class Voice(val sound: Sound, val prio: Int, val end: Double)

    private var voice: Voice? = null
    private val last = HashMap<Sound, Long>()

    var played = 0
        private set
    var dropped = 0
        private set
    var cut = 0
        private set

    /** The sound still sounding, if any. */
    fun sounding(): Sound? = voice?.takeIf { clock() < it.end }?.sound

    /** ms until the current voice ends (0 when silent). */
    fun remainingMs(): Long = voice?.let { max(0.0, it.end - clock()) }?.toLong() ?: 0L

    fun offer(sound: Sound, repeat: Boolean, output: SfxOutput): SfxDecision {
        if (!enabled) return SfxDecision.Drop(SfxDecision.Reason.Disabled, false)
        if (playerUp) return SfxDecision.Drop(SfxDecision.Reason.PlayerUp, false)
        if (hidden) return SfxDecision.Drop(SfxDecision.Reason.Hidden, false)
        val t = clock()
        val gap = if (repeat && sound.holdGapMs != null) sound.holdGapMs else sound.gapMs
        val prev = last[sound]
        if (prev != null && t - prev < gap) {
            dropped += 1
            return SfxDecision.Drop(SfxDecision.Reason.Gap, true)
        }
        val v = voice
        if (v != null && t < v.end && v.prio > sound.prio) {
            dropped += 1
            return SfxDecision.Drop(SfxDecision.Reason.Priority, true)
        }
        when (output) {
            SfxOutput.None -> return SfxDecision.Drop(SfxDecision.Reason.NoOutput, false)
            SfxOutput.Suspended -> {
                dropped += 1
                return SfxDecision.Drop(SfxDecision.Reason.Suspended, true)
            }
            SfxOutput.Running -> Unit
        }
        val cutting = v != null && t < v.end
        if (cutting) cut += 1
        voice = Voice(sound, sound.prio, t + durationMs(sound))
        last[sound] = t
        played += 1
        val gain = SFX_MASTER * sound.gain * (if (repeat && sound == Sound.Move) SFX_HELD_MOVE_GAIN else 1f)
        return SfxDecision.Play(sound, gain, cutting)
    }

    /** `silence()`: fade out whatever is sounding and forget the voice. */
    fun silence() {
        voice = null
    }
}
