package com.slumber.sleepsounds

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private var isPlaying = false
    private var isProUser = false
    private var currentNoiseType = "rain"
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null
    private var countDownTimer: CountDownTimer? = null

    private lateinit var txtPlayingSound: TextView
    private lateinit var txtTimerCountdown: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var adView: AdView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        MobileAds.initialize(this) {}
        adView = findViewById(R.id.adView)
        val adRequest = AdRequest.Builder().build()
        adView.loadAd(adRequest)

        txtPlayingSound = findViewById(R.id.txtPlayingSound)
        txtTimerCountdown = findViewById(R.id.txtTimerCountdown)
        btnPlayPause = findViewById(R.id.btnPlayPause)

        btnPlayPause.setOnClickListener {
            if (isPlaying) stopSound() else startSound()
        }

        findViewById<MaterialButton>(R.id.btnSoundRain).setOnClickListener {
            selectSound("rain", "🌧️ Gentle Rain")
        }
        findViewById<MaterialButton>(R.id.btnSoundNature).setOnClickListener {
            selectSound("nature", "🍃 Nature Night")
        }
        findViewById<MaterialButton>(R.id.btnSoundOcean).setOnClickListener {
            selectSound("ocean", "🌊 Calm Ocean Waves")
        }
        findViewById<MaterialButton>(R.id.btnSoundBrown).setOnClickListener {
            selectSound("brown", "🟤 Deep Brown Noise")
        }
        findViewById<MaterialButton>(R.id.btnSoundFan).setOnClickListener {
            selectSound("fan", "💨 Bedroom Fan")
        }
        findViewById<MaterialButton>(R.id.btnSoundWhite).setOnClickListener {
            selectSound("white", "⚪ Pure White Noise")
        }

        findViewById<MaterialButton>(R.id.btnTimer15).setOnClickListener { setTimer(15) }
        findViewById<MaterialButton>(R.id.btnTimer30).setOnClickListener { setTimer(30) }
        findViewById<MaterialButton>(R.id.btnTimer60).setOnClickListener { setTimer(60) }
        findViewById<MaterialButton>(R.id.btnTimerOff).setOnClickListener { cancelTimer() }

        findViewById<MaterialButton>(R.id.btnPro).setOnClickListener {
            showSubscriptionPlans()
        }
    }

    private fun selectSound(type: String, displayName: String) {
        currentNoiseType = type
        txtPlayingSound.text = displayName
        if (isPlaying) {
            stopSound()
            startSound()
        }
    }

    private fun startSound() {
        isPlaying = true
        btnPlayPause.text = "⏸"
        if (txtPlayingSound.text == "Select a sound to sleep") {
            txtPlayingSound.text = "🌧️ Gentle Rain"
        }

        val sampleRate = 44100
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

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
            .build()

        audioTrack?.play()

        audioThread = thread(start = true) {
            val random = Random()
            val buffer = ShortArray(bufferSize)
            var lastValue = 0f
            var timeStep = 0L

            while (isPlaying) {
                for (i in buffer.indices) {
                    timeStep++
                    val white = (random.nextFloat() * 2f - 1f)

                    val sample = when (currentNoiseType) {
                        "nature" -> {
                            // Forest wind + Cricket chirps
                            lastValue = (lastValue + (0.02f * white)) / 1.02f
                            val cricketPhase = (timeStep * 4500.0 * 2.0 * Math.PI / sampleRate)
                            val chirpEnvelope = (sin(timeStep * 24.0 * 2.0 * Math.PI / sampleRate)).toFloat()
                            val cricket = if (chirpEnvelope > 0.85f) (sin(cricketPhase).toFloat() * 0.18f) else 0f
                            (lastValue * 1.8f + cricket)
                        }
                        "ocean" -> {
                            // Ocean waves swell (10-second ebb and flow cycle)
                            lastValue = (lastValue + (0.025f * white)) / 1.025f
                            val waveSwell = 0.5f + 0.5f * sin(timeStep * 0.1 * 2.0 * Math.PI / sampleRate).toFloat()
                            (lastValue * (1.5f + waveSwell * 2.2f))
                        }
                        "brown" -> {
                            lastValue = (lastValue + (0.02f * white)) / 1.02f
                            lastValue * 3.5f
                        }
                        "rain" -> {
                            lastValue = (lastValue + (0.05f * white)) / 1.05f
                            val drop = if (random.nextFloat() > 0.995f) 0.8f else 0f
                            (lastValue * 1.5f + drop)
                        }
                        "fan" -> {
                            lastValue = (lastValue + (0.015f * white)) / 1.015f
                            lastValue * 2.8f
                        }
                        else -> white * 0.4f
                    }
                    val clamped = sample.coerceIn(-1.0f, 1.0f)
                    buffer[i] = (clamped * 32767).toInt().toShort()
                }
                audioTrack?.write(buffer, 0, buffer.size)
            }
        }
    }

    private fun stopSound() {
        isPlaying = false
        btnPlayPause.text = "▶"
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
        audioThread = null
    }

    private fun setTimer(minutes: Long) {
        countDownTimer?.cancel()
        val millis = minutes * 60 * 1000
        countDownTimer = object : CountDownTimer(millis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val mins = millisUntilFinished / 1000 / 60
                val secs = (millisUntilFinished / 1000) % 60
                txtTimerCountdown.text = "Timer: %02d:%02d".format(mins, secs)
            }
            override fun onFinish() {
                txtTimerCountdown.text = "Timer finished"
                stopSound()
            }
        }.start()
        Toast.makeText(this, "Timer set for $minutes minutes", Toast.LENGTH_SHORT).show()
    }

    private fun cancelTimer() {
        countDownTimer?.cancel()
        txtTimerCountdown.text = ""
        Toast.makeText(this, "Timer turned off", Toast.LENGTH_SHORT).show()
    }

    private fun showSubscriptionPlans() {
        val plans = arrayOf(
            "Monthly: $1.99 / month",
            "Quarterly: $4.99 / 3 months",
            "Yearly (Best Value): $14.99 / year (7 Days Free Trial)",
            "Lifetime Access: $24.99 (Pay Once, Keep Forever)"
        )

        AlertDialog.Builder(this)
            .setTitle("Upgrade to Slumber Pro ⭐")
            .setItems(plans) { _, which ->
                isProUser = true
                adView.visibility = View.GONE
                findViewById<MaterialButton>(R.id.btnPro).visibility = View.GONE
                Toast.makeText(this, "Pro Activated: Ads Removed!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSound()
        countDownTimer?.cancel()
    }
}
