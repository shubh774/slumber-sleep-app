package com.slumber.sleepsounds

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

/**
 * Deliberately NOT a ViewPager2/Fragment carousel -- that's a lot of moving parts for 3
 * static screens. A simple index + "Next" button is easier to get right and just as
 * effective for a 3-screen intro.
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Page(val emoji: String, val title: String, val body: String)

    private val pages = listOf(
        Page(
            "\uD83C\uDF19",
            "Welcome to Slumber",
            "Calming, procedurally-generated sounds to help you fall asleep and stay asleep -- no ads blaring, no pre-recorded loops that repeat awkwardly."
        ),
        Page(
            "\uD83C\uDFB5",
            "Mix and match sounds",
            "Tap more than one sound card to layer them together -- try Rain + Fan, or Ocean + Crickets -- and find your own perfect combination."
        ),
        Page(
            "\uD83D\uDD12",
            "Keeps playing after you lock your phone",
            "Slumber plays in the background, with controls right on your lock screen and notification shade, so you can turn it off without picking up your phone."
        )
    )

    private var currentPage = 0

    private lateinit var prefs: SharedPreferences
    private lateinit var emojiView: TextView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var nextButton: MaterialButton
    private lateinit var skipButton: MaterialButton
    private lateinit var dots: List<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("slumber_prefs", MODE_PRIVATE)

        // If already seen, skip straight to the app -- don't make returning users sit through it.
        if (prefs.getBoolean("onboarding_shown", false)) {
            launchMain()
            return
        }

        setContentView(R.layout.activity_onboarding)

        emojiView = findViewById(R.id.onboardingEmoji)
        titleView = findViewById(R.id.onboardingTitle)
        bodyView = findViewById(R.id.onboardingBody)
        nextButton = findViewById(R.id.btnOnboardingNext)
        skipButton = findViewById(R.id.btnOnboardingSkip)
        dots = listOf(
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3)
        )

        nextButton.setOnClickListener {
            if (currentPage < pages.size - 1) {
                currentPage++
                renderPage()
            } else {
                finishOnboarding()
            }
        }
        skipButton.setOnClickListener { finishOnboarding() }

        renderPage()
    }

    private fun renderPage() {
        val page = pages[currentPage]
        emojiView.text = page.emoji
        titleView.text = page.title
        bodyView.text = page.body
        nextButton.text = if (currentPage == pages.size - 1) "Get Started" else "Next"
        skipButton.visibility = if (currentPage == pages.size - 1) View.INVISIBLE else View.VISIBLE

        dots.forEachIndexed { index, dot ->
            dot.setBackgroundResource(
                if (index == currentPage) R.drawable.dot_active else R.drawable.dot_inactive
            )
        }
    }

    private fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_shown", true).apply()
        launchMain()
    }

    private fun launchMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
