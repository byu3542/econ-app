package com.economic.dashboard.ui.assistant

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import com.economic.dashboard.R
import com.economic.dashboard.utils.MotionUtil

/**
 * Pantrii port U7 (R5, KTD9): the loader row's view (view_assistant_loader).
 * It owns the themed rotation, so the adapter only says whether a tool phrase
 * is overriding it. A themed phrase crossfades every [LoadingPhrases.ROTATE_MS];
 * with animations off it swaps without the fade. No phrase is replaced sooner
 * than [LoadingPhrases.MIN_PHRASE_MS] after it appeared.
 */
class AssistantLoaderView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val phrases = LoadingPhrases(resources.getStringArray(R.array.assistant_loading_phrases).toList())
    private lateinit var tvPhrase: TextView

    private var running = false
    private var toolPhrase: String? = null
    private var shownAt = 0L
    private val advance = Runnable { showNext() }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tvPhrase = findViewById(R.id.tvLoaderPhrase)
    }

    /** Shows the loader, or updates the running one. [tool] overrides the
     *  themed rotation while non-null. Safe to call on every bind. */
    fun bind(tool: String?) {
        if (!running) {
            running = true
            toolPhrase = tool
            setPhrase(tool ?: phrases.next(), fade = false)
            scheduleRotation()
            return
        }
        if (tool == toolPhrase) return
        toolPhrase = tool
        removeCallbacks(advance)
        val wait = shownAt + LoadingPhrases.MIN_PHRASE_MS - SystemClock.uptimeMillis()
        if (wait > 0) postDelayed(advance, wait) else showNext()
    }

    /** Stops the rotation; the next [bind] starts afresh. */
    fun stop() {
        running = false
        toolPhrase = null
        removeCallbacks(advance)
        tvPhrase.animate().cancel()
        tvPhrase.alpha = 1f
    }

    private fun showNext() {
        if (!running) return
        setPhrase(toolPhrase ?: phrases.next(), fade = true)
        scheduleRotation()
    }

    /** Only themed phrases rotate; a tool phrase holds until the tool is done. */
    private fun scheduleRotation() {
        removeCallbacks(advance)
        if (toolPhrase == null) postDelayed(advance, LoadingPhrases.ROTATE_MS)
    }

    private fun setPhrase(text: String, fade: Boolean) {
        shownAt = SystemClock.uptimeMillis()
        tvPhrase.animate().cancel()
        if (!fade || !MotionUtil.animationsEnabled(context)) {
            tvPhrase.alpha = 1f
            tvPhrase.text = text
            return
        }
        tvPhrase.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            tvPhrase.text = text
            tvPhrase.animate().alpha(1f).setDuration(FADE_MS).start()
        }.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) scheduleRotation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(advance)
        tvPhrase.animate().cancel()
        tvPhrase.alpha = 1f
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Each half of the crossfade: out, swap, in. */
        const val FADE_MS = 150L
    }
}
