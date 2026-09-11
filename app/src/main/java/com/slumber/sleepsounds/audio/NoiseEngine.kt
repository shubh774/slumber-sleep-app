package com.slumber.sleepsounds.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every sound this app can play, procedurally generated -- no audio files, so adding a sound
 * costs a few lines of DSP code instead of megabytes of APK size.
 */
enum class NoiseType(val id: String, val displayName: String, val emoji: String) {
    RAIN("rain", "Gentle Rain", "\uD83C\uDF27\uFE0F"),
    THUNDERSTORM("thunderstorm", "Thunderstorm", "\u26C8\uFE0F"),
    OCEAN("ocean", "Ocean Waves", "\uD83C\uDF0A"),
    WATERFALL("waterfall", "Waterfall", "\uD83C\uDFDE\uFE0F"),
    STREAM("stream", "Bubbling Stream", "\uD83D\uDCA7"),
    WIND("wind", "Howling Wind", "\uD83C\uDF2C\uFE0F"),
    NATURE("nature", "Crickets at Night", "\uD83E\uDD97"),
    CAMPFIRE("campfire", "Crackling Campfire", "\uD83D\uDD25"),
    CAFE("cafe", "Coffee Shop Murmur", "\u2615"),
    TRAIN("train", "Night Train", "\uD83D\uDE82"),
    HEARTBEAT("heartbeat", "Heartbeat", "\uD83D\uDC93"),
    SINGING_BOWL("singing_bowl", "Singing Bowl", "\uD83C\uDFB6"),
    UNDERWATER("underwater", "Underwater Calm", "\uD83E\uDEE7"),
    SPACE("space", "Deep Space", "\uD83C\uDF0C"),
    BROWN("brown", "Brown Noise", "\uD83D\uDFE4"),
    PINK("pink", "Pink Noise", "\uD83C\uDF38"),
    FAN("fan", "Bedroom Fan", "\uD83D\uDCA8"),
    WHITE("white", "White Noise", "\u26AA");

    companion object {
        fun fromId(id: String): NoiseType = values().firstOrNull { it.id == id } ?: RAIN
    }
}

/**
 * Owns the persistent DSP state (filter memory, LFO phase, event timers) for ONE sound.
 * Kept as a separate instance per NoiseType so several of these can run at once and be
 * summed together for mixing, each with its own independent, continuous state.
 */
private class SoundGenerator(private val type: NoiseType) {
    private val random = Random()
    private var timeStep = 0L

    // General-purpose leaky-integrator low-pass filter state, reused by most noise-based
    // sounds (this is the same technique as the original single-sound engine).
    private var lp1 = 0f
    private var lp2 = 0f

    // Paul Kellet's economy pink-noise filter state (public-domain DSP technique).
    private var pb0 = 0f
    private var pb1 = 0f
    private var pb2 = 0f

    // Event-driven sounds (thunder rumbles, fire pops, train chugs).
    private var eventEnvelope = 0f

    fun nextSample(sampleRate: Int): Float {
        timeStep++
        val white = random.nextFloat() * 2f - 1f
        return when (type) {
            NoiseType.WHITE -> white * 0.35f
            NoiseType.PINK -> pink(white)
            NoiseType.BROWN -> {
                lp1 = (lp1 + 0.02f * white) / 1.02f
                lp1 * 3.2f
            }
            NoiseType.FAN -> {
                lp1 = (lp1 + 0.015f * white) / 1.015f
                lp1 * 2.6f
            }
            NoiseType.RAIN -> rain(white)
            NoiseType.THUNDERSTORM -> thunderstorm(white)
            NoiseType.OCEAN -> ocean(white, sampleRate)
            NoiseType.WATERFALL -> {
                lp1 = (lp1 + 0.08f * white) / 1.08f
                lp1 * 2.2f
            }
            NoiseType.STREAM -> stream(white, sampleRate)
            NoiseType.WIND -> wind(white, sampleRate)
            NoiseType.NATURE -> nature(white, sampleRate)
            NoiseType.CAMPFIRE -> campfire(white)
            NoiseType.CAFE -> cafe(white, sampleRate)
            NoiseType.TRAIN -> train(white, sampleRate)
            NoiseType.HEARTBEAT -> heartbeat(sampleRate)
            NoiseType.SINGING_BOWL -> singingBowl(sampleRate)
            NoiseType.UNDERWATER -> underwater(white, sampleRate)
            NoiseType.SPACE -> space(sampleRate)
        }
    }

    private fun pink(white: Float): Float {
        pb0 = 0.99765f * pb0 + white * 0.0990460f
        pb1 = 0.96300f * pb1 + white * 0.2965164f
        pb2 = 0.57000f * pb2 + white * 1.0526913f
        return (pb0 + pb1 + pb2 + white * 0.1848f) * 0.11f
    }

    private fun rain(white: Float): Float {
        lp1 = (lp1 + 0.05f * white) / 1.05f
        val droplet = if (random.nextFloat() > 0.995f) 0.8f else 0f
        return lp1 * 1.5f + droplet
    }

    private fun thunderstorm(white: Float): Float {
        lp1 = (lp1 + 0.05f * white) / 1.05f
        val rainBed = lp1 * 1.3f + (if (random.nextFloat() > 0.996f) 0.6f else 0f)

        if (eventEnvelope < 0.0005f && random.nextFloat() > 0.99998f) {
            eventEnvelope = 1f // start a new rumble, very rarely
        }
        if (eventEnvelope > 0.0005f) {
            lp2 = (lp2 + 0.01f * white) / 1.01f // heavier low-pass = distant rumble
            val rumble = lp2 * 4f * eventEnvelope
            eventEnvelope *= 0.99993f // slow decay over a few seconds
            return (rainBed + rumble).coerceIn(-1f, 1f)
        }
        return rainBed
    }

    private fun ocean(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.025f * white) / 1.025f
        val swell = 0.5f + 0.5f * sin(timeStep * 0.1 * 2.0 * PI / sampleRate).toFloat()
        return lp1 * (1.5f + swell * 2.2f)
    }

    private fun stream(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.12f * white) / 1.12f
        val flutter = sin(timeStep * 35.0 * 2.0 * PI / sampleRate).toFloat() * white * 0.08f
        return lp1 * 1.6f + flutter
    }

    private fun wind(white: Float, sampleRate: Int): Float {
        val cutoffMod = 0.01f + 0.02f * (0.5f + 0.5f * sin(timeStep * 0.05 * 2.0 * PI / sampleRate).toFloat())
        lp1 = (lp1 + cutoffMod * white) / (1f + cutoffMod)
        val gustSwell = 0.6f + 0.4f * sin(timeStep * 0.07 * 2.0 * PI / sampleRate + 1.3).toFloat()
        return lp1 * 3.0f * gustSwell
    }

    private fun nature(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.02f * white) / 1.02f
        val cricketPhase = timeStep * 4500.0 * 2.0 * PI / sampleRate
        val chirpEnvelope = sin(timeStep * 24.0 * 2.0 * PI / sampleRate).toFloat()
        val cricket = if (chirpEnvelope > 0.85f) sin(cricketPhase).toFloat() * 0.18f else 0f
        return lp1 * 1.8f + cricket
    }

    private fun campfire(white: Float): Float {
        lp1 = (lp1 + 0.03f * white) / 1.03f
        val bed = lp1 * 0.8f
        if (eventEnvelope < 0.001f && random.nextFloat() > 0.9995f) {
            eventEnvelope = 1f // trigger a crackle pop
        }
        var pop = 0f
        if (eventEnvelope > 0.001f) {
            pop = white * eventEnvelope * 0.9f
            eventEnvelope *= 0.995f // fast decay -> short pop
        }
        return bed + pop
    }

    private fun cafe(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.06f * white) / 1.06f
        lp2 = (lp2 + 0.015f * white) / 1.015f
        val bandpassIsh = (lp1 - lp2) * 1.8f
        val murmurSwell = 0.6f + 0.4f * sin(timeStep * 0.03 * 2.0 * PI / sampleRate).toFloat()
        return bandpassIsh * murmurSwell
    }

    private fun train(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.04f * white) / 1.04f
        val bed = lp1 * 0.7f
        val chugPeriod = sampleRate / 2 // ~2 chugs per second
        val chugPhase = (timeStep % chugPeriod).toFloat() / chugPeriod
        val chugEnvelope = exp(-chugPhase * 8.0).toFloat()
        return bed + chugEnvelope * 0.45f
    }

    private fun heartbeat(sampleRate: Int): Float {
        val period = sampleRate // one heartbeat cycle per second
        val phase = (timeStep % period).toFloat() / period
        fun thump(center: Float): Float {
            val d = abs(phase - center)
            val width = 0.035f
            return if (d < width) cos((d / width) * (PI / 2)).toFloat() else 0f
        }
        val envelope = thump(0f) + thump(0.16f) * 0.7f
        val tone = sin(timeStep * 52.0 * 2.0 * PI / sampleRate).toFloat()
        return tone * envelope * 0.8f
    }

    private fun singingBowl(sampleRate: Int): Float {
        val vibrato = 1f + 0.003f * sin(timeStep * 5.0 * 2.0 * PI / sampleRate).toFloat()
        val fundamental = 136.1 * vibrato
        val tone = sin(timeStep * fundamental * 2.0 * PI / sampleRate).toFloat() * 0.5f +
            sin(timeStep * fundamental * 2 * 2.0 * PI / sampleRate).toFloat() * 0.2f +
            sin(timeStep * fundamental * 3 * 2.0 * PI / sampleRate).toFloat() * 0.1f
        return tone * 0.55f
    }

    private fun underwater(white: Float, sampleRate: Int): Float {
        lp1 = (lp1 + 0.008f * white) / 1.008f
        val swell = 0.7f + 0.3f * sin(timeStep * 0.04 * 2.0 * PI / sampleRate).toFloat()
        return lp1 * 5f * swell
    }

    private fun space(sampleRate: Int): Float {
        val t = timeStep.toDouble() / sampleRate
        val pad = (sin(2.0 * PI * 55.0 * t) + sin(2.0 * PI * 55.3 * t) + sin(2.0 * PI * 82.5 * t) * 0.5).toFloat()
        val slowSwell = 0.5f + 0.5f * sin(timeStep * 0.02 * 2.0 * PI / sampleRate).toFloat()
        return pad * 0.16f * slowSwell
    }
}

/**
 * Owns the AudioTrack + generator thread and supports mixing several sounds together at
 * once (e.g. Rain + Fan), which is the #1 requested feature in this category of app.
 *
 * - toggleSound(type) adds/removes a sound from the active mix. Selecting 3 sounds and
 *   hitting play mixes all 3.
 * - pause()/resume() stop/restart audio output WITHOUT forgetting which sounds were
 *   selected, so the play button toggles the whole mix on/off.
 * - Start/stop/switch are crossfaded (~150ms) to avoid clicks, same as before.
 */
class NoiseEngine {

    private val activeTypes = CopyOnWriteArraySet<NoiseType>()
    private val generators = ConcurrentHashMap<NoiseType, SoundGenerator>()

    @Volatile
    private var isRunning = false

    @Volatile
    private var targetGain = 0f

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    val isPlaying: Boolean get() = isRunning
    fun currentActiveTypes(): Set<NoiseType> = activeTypes.toSet()

    /** Adds or removes [type] from the mix. Auto-starts on first sound, auto-stops on last removed. */
    fun toggleSound(type: NoiseType) {
        if (activeTypes.contains(type)) {
            activeTypes.remove(type)
        } else {
            generators.getOrPut(type) { SoundGenerator(type) }
            activeTypes.add(type)
        }
        if (activeTypes.isEmpty()) {
            pause()
        } else if (!isRunning) {
            resume()
        }
    }

    /** Resumes playing whatever sounds are currently selected (no-op if nothing selected). */
    fun resume() {
        if (activeTypes.isEmpty() || isRunning) return
        isRunning = true
        targetGain = 1f
        startPlaybackThread()
    }

    /** Stops audio output but keeps the selected sounds remembered for the next resume(). */
    fun pause() {
        isRunning = false
        targetGain = 0f
        playbackThread = null
    }

    fun release() {
        activeTypes.clear()
        pause()
    }

    private fun startPlaybackThread() {
        val sampleRate = 44100
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = minBuffer * 2

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()

        playbackThread = thread(start = true, name = "NoiseEngine-Playback") {
            val buffer = ShortArray(minBuffer)
            var currentGain = 0f
            val gainStep = 1f / (sampleRate * 0.15f)

            while (isRunning || currentGain > 0f) {
                // Snapshot once per buffer (not per sample) to keep the hot loop cheap.
                val active = activeTypes.toTypedArray()
                val mixCompensation = if (active.size <= 1) 1f else 1f / sqrt(active.size.toFloat())

                for (i in buffer.indices) {
                    var mixed = 0f
                    for (t in active) {
                        mixed += generators[t]?.nextSample(sampleRate) ?: 0f
                    }
                    mixed *= mixCompensation

                    if (currentGain < targetGain) {
                        currentGain = (currentGain + gainStep).coerceAtMost(targetGain)
                    } else if (currentGain > targetGain) {
                        currentGain = (currentGain - gainStep).coerceAtLeast(targetGain)
                    }

                    val clamped = (mixed * currentGain).coerceIn(-1f, 1f)
                    buffer[i] = (clamped * 32767).toInt().toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)

                if (!isRunning && currentGain <= 0f) break
            }

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {
                // AudioTrack can throw if the device already tore it down; safe to ignore.
            }
            audioTrack = null
        }
    }
}
