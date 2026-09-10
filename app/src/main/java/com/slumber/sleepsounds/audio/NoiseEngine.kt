package com.slumber.sleepsounds.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.sin

/**
 * The sound types this app can generate procedurally (no audio files -> tiny APK size).
 */
enum class NoiseType(val id: String, val displayName: String, val emoji: String) {
    RAIN("rain", "Gentle Rain", "\uD83C\uDF27\uFE0F"),
    NATURE("nature", "Nature Night", "\uD83C\uDF43"),
    OCEAN("ocean", "Calm Ocean Waves", "\uD83C\uDF0A"),
    BROWN("brown", "Deep Brown Noise", "\uD83D\uDFE4"),
    FAN("fan", "Bedroom Fan", "\uD83D\uDCA8"),
    WHITE("white", "Pure White Noise", "\u26AA");

    companion object {
        fun fromId(id: String): NoiseType = values().firstOrNull { it.id == id } ?: RAIN
    }
}

/**
 * Owns the AudioTrack + generator thread. This class knows nothing about Activities or
 * Services on purpose -- it is safe to run from a foreground Service so playback survives
 * the screen turning off, which is the whole point of a sleep-sounds app.
 *
 * Sound switches and stop() are crossfaded (~150ms) instead of hard-cut, so you never hear
 * a click/pop -- important because this plays right next to someone's ear while they sleep.
 */
class NoiseEngine {

    @Volatile
    var currentType: NoiseType = NoiseType.RAIN
        private set

    @Volatile
    private var isRunning = false

    // 0f..1f master gain, ramped smoothly to avoid clicks on start/stop/switch.
    @Volatile
    private var targetGain = 0f

    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    val isPlaying: Boolean get() = isRunning

    fun start(type: NoiseType) {
        currentType = type
        if (isRunning) return
        isRunning = true
        targetGain = 1f

        val sampleRate = 44100
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // A couple of buffer-periods of headroom avoids underrun crackle on slower devices.
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
            val random = Random()
            val buffer = ShortArray(minBuffer)
            var lastValue = 0f
            var timeStep = 0L
            var currentGain = 0f
            // ~150ms fade, expressed as a per-sample step.
            val gainStep = 1f / (sampleRate * 0.15f)

            while (isRunning || currentGain > 0f) {
                val type = currentType
                for (i in buffer.indices) {
                    timeStep++
                    val white = random.nextFloat() * 2f - 1f

                    val sample = when (type) {
                        NoiseType.NATURE -> {
                            lastValue = (lastValue + (0.02f * white)) / 1.02f
                            val cricketPhase = timeStep * 4500.0 * 2.0 * Math.PI / sampleRate
                            val chirpEnvelope = sin(timeStep * 24.0 * 2.0 * Math.PI / sampleRate).toFloat()
                            val cricket = if (chirpEnvelope > 0.85f) sin(cricketPhase).toFloat() * 0.18f else 0f
                            lastValue * 1.8f + cricket
                        }
                        NoiseType.OCEAN -> {
                            lastValue = (lastValue + (0.025f * white)) / 1.025f
                            val waveSwell = 0.5f + 0.5f * sin(timeStep * 0.1 * 2.0 * Math.PI / sampleRate).toFloat()
                            lastValue * (1.5f + waveSwell * 2.2f)
                        }
                        NoiseType.BROWN -> {
                            lastValue = (lastValue + (0.02f * white)) / 1.02f
                            lastValue * 3.5f
                        }
                        NoiseType.RAIN -> {
                            lastValue = (lastValue + (0.05f * white)) / 1.05f
                            val drop = if (random.nextFloat() > 0.995f) 0.8f else 0f
                            lastValue * 1.5f + drop
                        }
                        NoiseType.FAN -> {
                            lastValue = (lastValue + (0.015f * white)) / 1.015f
                            lastValue * 2.8f
                        }
                        NoiseType.WHITE -> white * 0.4f
                    }

                    // Smoothly chase targetGain every sample -- this is what removes the
                    // click when start()/stop()/switchTo() are called.
                    if (currentGain < targetGain) {
                        currentGain = (currentGain + gainStep).coerceAtMost(targetGain)
                    } else if (currentGain > targetGain) {
                        currentGain = (currentGain - gainStep).coerceAtLeast(targetGain)
                    }

                    val clamped = (sample * currentGain).coerceIn(-1.0f, 1.0f)
                    buffer[i] = (clamped * 32767).toInt().toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)

                // Once the caller asked us to stop AND we've faded silent, tear down.
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

    /** Crossfades to a new sound instead of a hard stop/start (no click, no gap). */
    fun switchTo(type: NoiseType) {
        if (!isRunning) {
            start(type)
            return
        }
        currentType = type
    }

    fun stop() {
        isRunning = false
        targetGain = 0f
        // Don't join() here -- the fade-out + teardown happens on the playback thread and
        // this may be called from the main thread; joining would block the UI.
        playbackThread = null
    }

    fun release() {
        stop()
    }
}
