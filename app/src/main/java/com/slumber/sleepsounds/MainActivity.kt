package com.slumber.sleepsounds

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.slumber.sleepsounds.audio.NoiseType
import com.slumber.sleepsounds.billing.BillingManager
import com.slumber.sleepsounds.service.SleepSoundService

class MainActivity : AppCompatActivity(), SleepSoundService.PlaybackListener {

    private var service: SleepSoundService? = null
    private var isBound = false
    // If the user taps a control in the brief async window before the service finishes
    // binding, we stash the action here and run it as soon as onServiceConnected fires,
    // instead of silently dropping the tap.
    private var pendingAction: (() -> Unit)? = null

    private lateinit var billingManager: BillingManager

    private lateinit var txtPlayingSound: TextView
    private lateinit var txtTimerCountdown: TextView
    private lateinit var txtStreak: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var adView: AdView
    private lateinit var cardPro: MaterialCardView
    private lateinit var soundGrid: GridLayout

    // Each sound card, keyed by type, so we can highlight every card that's part of the
    // current mix (multiple can be highlighted at once now that mixing is supported).
    private lateinit var soundCards: Map<NoiseType, MaterialCardView>
    private lateinit var timerButtons: Map<Int, MaterialButton>

    // Simple streak/gamification: counts consecutive days with at least one real listening
    // session (3+ minutes). Same "engagement loop" Calm/Headspace use for retention.
    private lateinit var prefs: SharedPreferences
    private var sessionStartMillis: Long? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whether granted or not, move on to the next onboarding step.
            maybeRequestBatteryExemption()
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as SleepSoundService.LocalBinder
            service = localBinder.getService()
            service?.setListener(this@MainActivity)
            isBound = true
            // Reflect whatever state the service is already in (e.g. Activity was recreated
            // while sound kept playing in the background).
            onStateChanged(service?.isPlaying() ?: false, service?.activeSounds() ?: emptySet())
            pendingAction?.invoke()
            pendingAction = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtPlayingSound = findViewById(R.id.txtPlayingSound)
        txtTimerCountdown = findViewById(R.id.txtTimerCountdown)
        txtStreak = findViewById(R.id.txtStreak)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        adView = findViewById(R.id.adView)
        cardPro = findViewById(R.id.cardPro)
        soundGrid = findViewById(R.id.soundGrid)
        prefs = getSharedPreferences("slumber_prefs", Context.MODE_PRIVATE)

        soundCards = buildSoundGrid()
        timerButtons = mapOf(
            15 to findViewById(R.id.btnTimer15),
            30 to findViewById(R.id.btnTimer30),
            60 to findViewById(R.id.btnTimer60)
        )

        setupBilling()
        setupAds()
        setupClickListeners()
        updateStreakDisplay()
        maybeRequestNotificationPermission()
    }

    /** Inflates one card per NoiseType into the 3-column GridLayout and wires its tap-to-toggle. */
    private fun buildSoundGrid(): Map<NoiseType, MaterialCardView> {
        val inflater = LayoutInflater.from(this)
        val columns = 3
        val cards = mutableMapOf<NoiseType, MaterialCardView>()

        NoiseType.values().forEachIndexed { index, type ->
            val card = inflater.inflate(R.layout.item_sound_card, soundGrid, false) as MaterialCardView
            card.findViewById<TextView>(R.id.itemSoundEmoji).text = type.emoji
            card.findViewById<TextView>(R.id.itemSoundLabel).text = type.displayName

            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = dp(88)
                columnSpec = GridLayout.spec(index % columns, 1f)
                rowSpec = GridLayout.spec(index / columns)
                val margin = dp(5)
                setMargins(margin, margin, margin, margin)
            }
            card.layoutParams = params
            card.setOnClickListener { toggleSound(type) }
            soundGrid.addView(card)
            cards[type] = card
        }
        return cards
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupBilling() {
        billingManager = BillingManager(applicationContext) { isPro ->
            runOnUiThread { applyProStatus(isPro) }
        }
        // Reflect cached state immediately so ads don't flash on screen for a paying user
        // while we reconnect to Play in the background; refreshPurchases() re-verifies it.
        applyProStatus(billingManager.isProUserCached())
        billingManager.startConnection()
    }

    private fun setupAds() {
        MobileAds.initialize(this) {}
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun applyProStatus(isPro: Boolean) {
        adView.visibility = if (isPro) View.GONE else View.VISIBLE
        cardPro.visibility = if (isPro) View.GONE else View.VISIBLE
    }

    private fun setupClickListeners() {
        btnPlayPause.setOnClickListener {
            runOrQueue { it.togglePlayPause() }
        }

        timerButtons.forEach { (minutes, button) ->
            button.setOnClickListener { setTimer(minutes) }
        }
        findViewById<MaterialButton>(R.id.btnTimerOff).setOnClickListener {
            service?.cancelTimer()
            txtTimerCountdown.text = ""
            highlightTimer(null)
        }

        cardPro.setOnClickListener { showSubscriptionPlans() }
    }

    private fun toggleSound(type: NoiseType) {
        runOrQueue { it.toggleSound(type) }
    }

    private fun setTimer(minutes: Int) {
        highlightTimer(minutes)
        runOrQueue { it.setTimer(minutes.toLong()) }
    }

    /** Visually marks every card that's currently part of the mix; the rest go back to default. */
    private fun highlightActiveSounds(active: Set<NoiseType>) {
        soundCards.forEach { (type, card) ->
            if (type in active) {
                card.strokeColor = ContextCompat.getColor(this, R.color.card_stroke_selected)
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg_selected))
            } else {
                card.strokeColor = ContextCompat.getColor(this, R.color.card_stroke_default)
                card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.card_bg_default))
            }
        }
    }

    /** Visually marks the active timer duration (or clears all when timer is turned off). */
    private fun highlightTimer(selectedMinutes: Int?) {
        timerButtons.forEach { (minutes, button) ->
            if (minutes == selectedMinutes) {
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.timer_bg_selected))
                button.setTextColor(ContextCompat.getColor(this, R.color.timer_text_selected))
            } else {
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.timer_bg_default))
                button.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            }
        }
        val offButton = findViewById<MaterialButton>(R.id.btnTimerOff)
        if (selectedMinutes == null) {
            offButton.setBackgroundColor(ContextCompat.getColor(this, R.color.timer_bg_selected))
            offButton.setTextColor(ContextCompat.getColor(this, R.color.timer_text_selected))
        } else {
            offButton.setBackgroundColor(ContextCompat.getColor(this, R.color.timer_bg_default))
            offButton.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    /**
     * Always calls startService() before touching the service, every time -- it's idempotent
     * (safe on an already-running service) and guarantees the service keeps an independent
     * lifecycle that survives the Activity unbinding (see the background-playback fix notes
     * in SleepSoundService for why this matters).
     */
    private fun runOrQueue(action: (SleepSoundService) -> Unit) {
        startService(Intent(this, SleepSoundService::class.java))
        val current = service
        if (current != null) {
            action(current)
        } else {
            pendingAction = { service?.let(action) }
        }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return // battery step continues from the permission callback
            }
        }
        maybeRequestBatteryExemption()
    }

    /**
     * Many phones (Samsung's "Sleeping apps" / "Put unused apps to sleep", and similar
     * features on other brands) will kill background audio a few minutes after the screen
     * locks unless the app is exempted from battery optimization. We ask once, with a
     * plain-language explanation, rather than silently failing later.
     */
    private fun maybeRequestBatteryExemption() {
        if (prefs.getBoolean("asked_battery_exemption", false)) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("asked_battery_exemption", true).apply()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Keep sounds playing all night")
            .setMessage(
                "Some phones pause background audio to save battery. To make sure your " +
                    "sleep sound keeps playing after your screen locks, allow Slumber to run " +
                    "without battery restrictions."
            )
            .setPositiveButton("Allow") { _, _ ->
                prefs.edit().putBoolean("asked_battery_exemption", true).apply()
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    // A few OEM ROMs don't implement this action; nothing more we can do.
                }
            }
            .setNegativeButton("Not now") { _, _ ->
                prefs.edit().putBoolean("asked_battery_exemption", true).apply()
            }
            .setCancelable(false)
            .show()
    }

    /** Called when a listening session ends; counts it toward the daily streak if long enough. */
    private fun recordSessionIfSignificant(elapsedMillis: Long) {
        if (elapsedMillis < 3 * 60 * 1000L) return // too short to count as a real session

        val today = System.currentTimeMillis() / (1000L * 60 * 60 * 24)
        val lastDay = prefs.getLong("last_session_day", -1L)
        if (lastDay == today) return // already counted today

        val currentStreak = prefs.getInt("streak_count", 0)
        val newStreak = if (lastDay == today - 1) currentStreak + 1 else 1
        prefs.edit()
            .putLong("last_session_day", today)
            .putInt("streak_count", newStreak)
            .apply()
        updateStreakDisplay()
    }

    private fun updateStreakDisplay() {
        val streak = prefs.getInt("streak_count", 0)
        txtStreak.text = if (streak > 0) "\uD83D\uDD25 $streak day streak" else ""
        txtStreak.visibility = if (streak > 0) View.VISIBLE else View.GONE
    }

    private fun showSubscriptionPlans() {
        val plans = arrayOf(
            "Monthly: \$1.99 / month" to BillingManager.MONTHLY,
            "Quarterly: \$4.99 / 3 months" to BillingManager.QUARTERLY,
            "Yearly (Best Value): \$14.99 / year (7-day free trial)" to BillingManager.YEARLY,
            "Lifetime Access: \$24.99 (pay once)" to BillingManager.LIFETIME
        )

        AlertDialog.Builder(this)
            .setTitle("Upgrade to Slumber Pro \u2B50")
            .setItems(plans.map { it.first }.toTypedArray()) { _, which ->
                billingManager.launchPurchase(this, plans[which].second)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun mixLabel(active: Set<NoiseType>): String = when {
        active.isEmpty() -> "Tap sounds below to mix"
        active.size == 1 -> "${active.first().emoji} ${active.first().displayName}"
        active.size <= 3 -> active.joinToString(" + ") { "${it.emoji} ${it.displayName}" }
        else -> "${active.size} sounds mixing \uD83C\uDFB6"
    }

    // --- SleepSoundService.PlaybackListener ---

    override fun onStateChanged(isPlaying: Boolean, activeSounds: Set<NoiseType>) {
        runOnUiThread {
            txtPlayingSound.text = mixLabel(activeSounds)
            btnPlayPause.text = if (isPlaying) "\u23F8" else "\u25B6"
            highlightActiveSounds(activeSounds)

            if (isPlaying) {
                if (sessionStartMillis == null) sessionStartMillis = System.currentTimeMillis()
            } else {
                sessionStartMillis?.let { start ->
                    recordSessionIfSignificant(System.currentTimeMillis() - start)
                }
                sessionStartMillis = null
            }
        }
    }

    override fun onTimerTick(remainingMillis: Long) {
        runOnUiThread {
            val mins = remainingMillis / 1000 / 60
            val secs = (remainingMillis / 1000) % 60
            txtTimerCountdown.text = "Timer: %02d:%02d".format(mins, secs)
        }
    }

    override fun onTimerFinished() {
        runOnUiThread {
            txtTimerCountdown.text = "Timer finished"
            highlightTimer(null)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, SleepSoundService::class.java), connection, Context.BIND_AUTO_CREATE)
        billingManager.refreshPurchases()
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            service?.setListener(null)
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.endConnection()
        // Deliberately NOT stopping the service here -- if a sound is playing, it should
        // keep playing after the Activity is destroyed (screen off, app swiped, etc).
        // The service stops itself via stopPlayback() or when idle and the task is removed.
    }
}
