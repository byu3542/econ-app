package com.economic.dashboard.ui.assistant

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.economic.dashboard.ui.*
import com.economic.dashboard.ui.AiAnalystBottomSheet.StreamState
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.chooseModel
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.freshnessTag
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.isLookupAnnouncementOnly
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.isTransientFailure
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.looksToolLikely
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.plainError
import com.economic.dashboard.ui.AiAnalystBottomSheet.Companion.stripDanglingAnnouncement
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import androidx.core.os.HandlerCompat
import android.os.SystemClock
import android.text.Editable
import android.util.Log
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView

import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.economic.dashboard.R
import com.economic.dashboard.analyst.AnalystToolExecutor
import com.economic.dashboard.analyst.ChatSeriesData
import com.economic.dashboard.analyst.DerivedMetricsBuilder
import com.economic.dashboard.analyst.HistoricalContextBuilder
import com.economic.dashboard.analyst.ModelConfig
import com.economic.dashboard.analyst.AnalystIdentity
import com.economic.dashboard.analyst.NewsContextBuilder
import com.economic.dashboard.analyst.ReleaseCalendar
import com.economic.dashboard.analyst.SmartPromptGenerator
import com.economic.dashboard.api.ApiConfig
import com.economic.dashboard.cache.DashboardDataCache
import com.economic.dashboard.databinding.FragmentAssistantBinding
import com.economic.dashboard.models.ChatChart
import com.economic.dashboard.models.ChatMessage
import com.economic.dashboard.models.TurnSource
import com.economic.dashboard.models.EconomicDataPoint
import com.economic.dashboard.models.TraceStep
import com.economic.dashboard.models.TurnSources
import com.economic.dashboard.news.NewsItem
import com.economic.dashboard.news.NewsRepository
import com.economic.dashboard.ui.views.CentreOutStagger
import com.economic.dashboard.ui.views.PressFeedback
import com.economic.dashboard.utils.AppExecutors
import com.economic.dashboard.utils.HapticUtil
import com.economic.dashboard.utils.ImageUtils
import com.economic.dashboard.utils.SettingsManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

import org.json.JSONArray
import org.json.JSONObject

import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

class AssistantFragment : Fragment() {

    private var binding: FragmentAssistantBinding? = null

    /** True while a request is in flight — blocks double-sends. */
    private var awaitingResponse = false

    /**
     * AIC-02: whether the list should keep pinning to the bottom as a reply
     * streams in. Set by a send; cleared only when the user drags up, and set
     * again when they come back within [FOLLOW_SLOP_DP] of the bottom.
     */
    private var followStream = true

    /** AIC-05: answers Regenerate set aside, handed to the next turn. */
    private var pendingVariants: List<ChatMessage.AnswerVariant> = emptyList()

    /** AIC-03: the turn now streaming, and the question it answers. */
    private class ActiveTurn(val query: String, val st: StreamState)

    /**
     * AIC-03: exactly one of Stop and the finishing callback may commit a turn.
     * Whoever takes it out of here first owns the commit; the other backs off.
     */
    private val activeTurn = java.util.concurrent.atomic.AtomicReference<ActiveTurn?>(null)

    private fun claimTurn(st: StreamState): Boolean {
        val t = activeTurn.get() ?: return false
        return t.st === st && activeTurn.compareAndSet(t, null)
    }

    /**
     * 24-month historical trend block built from the Room cache.
     * Preloaded on a background thread in onViewCreated (Room queries are
     * synchronous and must not run on the main thread), then injected into
     * the system prompt by queryClaude(). Empty string if cache is empty.
     */
    @Volatile
    private var historicalContext = ""

    /** Application context captured at view creation. The tool loop runs on
     *  OkHttp threads and must not silently drop a tool round (finalizing a
     *  dangling "Let me fetch..." reply) just because the sheet detached. */
    @Volatile
    private var appContext: android.content.Context? = null

    /** Extra "the user is looking at X" block injected by entry points. */
    private var screenContext = ""

    /** AI Law 7: the NEXT turn came from a one-tap gesture → concise answer.
     *  Consumed (reset) by queryClaude so typed follow-ups get full length. */
    @Volatile
    private var conciseNextTurn = false

    /** Image attached to the next message (vision analysis), or null. */
    @Volatile
    private var pendingImage: ImageUtils.EncodedImage? = null

    /** In-flight API call — cancellable via the Stop button. */
    @Volatile
    private var currentCall: Call? = null

    /** Owns every scheduled auto-retry so onDestroyView/cancelCurrentCall can
     *  cancel a retry that's queued but hasn't fired yet — a bare `new
     *  Handler(...).postDelayed(...)` can't be cancelled from outside itself. */
    private val retryHandler = Handler(Looper.getMainLooper())

    /** AIC-09: batches stream repaints; each StreamState is its own token. */
    private val streamHandler = Handler(Looper.getMainLooper())

    /** Photo picker for the attach-image button. Registered in onCreate, which
     *  is the only point the Activity Result API allows. */
    private lateinit var pickImageLauncher: ActivityResultLauncher<String>

    /** Query queued to fire as soon as a shared image finishes encoding. */
    @Volatile
    private var pendingAutoSendQuery: String? = null

    /** Keeps the empty state and chip row in sync with the (host-owned) adapter. */
    private val conversationObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() { updateConversationStateViews() }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { updateConversationStateViews() }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { updateConversationStateViews() }
    }

    // ─── Convenience access to shared state in MainActivity ────────────────────

    private fun host(): MainActivity = requireActivity() as MainActivity
    private fun chatAdapter(): ChatAdapter = host().getChatAdapter()
    /** Safe off the main thread and after detach, unlike [host]. */
    private fun viewModelOrNull(): EconomicViewModel? = (activity as? MainActivity)?.getViewModel()
    private fun conversationHistory(): JSONArray = host().getConversationHistory()
    private fun httpClient(): okhttp3.OkHttpClient = host().getHttpClient()
    private fun lastUserQuery(): String? = host().getLastUserQuery()
    private fun setLastUserQuery(q: String) { host().setLastUserQuery(q) }

    /** Runs on the UI thread only if the sheet is still attached to its activity. */
    private fun runOnUi(r: Runnable) {
        val a = activity
        a?.runOnUiThread { if (isAdded) r.run() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickImageLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri -> if (uri != null) attachImage(uri, false) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val b = FragmentAssistantBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = binding ?: return

        val rvChat = b.rvChat
        val etMsg = b.etMessage
        val btnSend = b.btnSend
        val btnClose: View? = b.btnClose

        val chipYieldCurve: View? = b.chipYieldCurve
        val chipRecession: View? = b.chipRecession
        val chipWages: View? = b.chipWages
        val chipRecentUpdates: View? = b.chipRecentUpdates
        val chipRecentNews: View? = b.chipRecentNews

        // Multi-line input: wrap text instead of scrolling, keep the IME Send action
        etMsg.setHorizontallyScrolling(false)
        etMsg.maxLines = 4

        rvChat.layoutManager = LinearLayoutManager(requireContext())
        rvChat.adapter = chatAdapter()

        // Change animations off — streaming text rebinds would flicker otherwise
        val animator = rvChat.itemAnimator
        if (animator is androidx.recyclerview.widget.SimpleItemAnimator)
            animator.supportsChangeAnimations = false

        // Empty state + quick-ask chips react to conversation state
        chatAdapter().registerAdapterDataObserver(conversationObserver)
        chatAdapter().turnActions = turnActions // AIC-05
        updateConversationStateViews()

        // Preload the 24-month historical context from the Room cache so it's
        // ready before the first question is sent (Room must stay off the
        // main thread). Uses application context — safe if the sheet closes.
        val appCtx = requireContext().applicationContext
        appContext = appCtx
        AppExecutors.diskIO().execute { historicalContext = HistoricalContextBuilder.build(appCtx) }

        // AI Law 3: back get_series with the live ViewModel series the app
        // already charts, so a long-press on ANY fragment has data behind it.
        // WeakReference so the static provider can't leak a finished activity's
        // ViewModel; LiveData.getValue() is safe from the OkHttp tool thread.
        val vmRef = WeakReference(host().getViewModel())
        // LiveSeriesProvider is a `fun interface` (batch 9b) specifically so this
        // stays a lambda now that the file is Kotlin.
        AnalystToolExecutor.setLiveSeriesProvider { seriesId ->
            val lv = vmRef.get()
            if (lv == null) null
            else when (seriesId) {
                "CPI" -> {
                    val l = lv.getCpiData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "CPI-U All Items")
                }
                "PCE" -> {
                    val l = lv.getPceData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "PCE Price Index")
                }
                "CORE_PCE" -> {
                    val l = lv.getPceData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "Core PCE Price Index")
                }
                "SP500" -> lv.getSp500Data().getValue()
                "NASDAQ" -> lv.getNasdaqData().getValue()
                "VIX" -> lv.getVixData().getValue()
                "WAGES" -> {
                    val l = lv.getWageData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "Average Hourly Earnings - Private")
                }
                "HOUSING_STARTS" -> {
                    val l = lv.getHousingData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "Housing Starts")
                }
                "HOME_SALES" -> {
                    val l = lv.getHousingData().getValue()
                    if (l == null) null else EconomicViewModel.filterBySeries(l, "Existing Home Sales")
                }
                "BAA_SPREAD" -> lv.getBaaSpreadData().getValue()
                "HY_SPREAD" -> lv.getHySpreadData().getValue()
                else -> null
            }
        }

        if (chatAdapter().itemCount > 0)
            rvChat.scrollToPosition(chatAdapter().itemCount - 1)

        // ── Edge-to-edge insets (Pantrii port U3) ─────────────────────────────
        // AIC-10: the activity window draws edge to edge, so the IME never
        // resizes it; the root pads itself clear of the keyboard and the system
        // bars. MainActivity consumes insets at its root, so it forwards them
        // here explicitly (see MainActivity's insets listener).
        // U3: the assistant sits on the light ground, not the navy header,
        // so the status-bar icons go dark while it is up (light theme only).
        setLightSystemBars(true)
        view.post {
            val root = binding?.root ?: return@post
            val padTop = root.paddingTop
            val padBottom = root.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(v.paddingLeft, padTop + bars.top, v.paddingRight,
                    padBottom + Math.max(ime.bottom, bars.bottom))
                onImeVisibilityChanged(insets.isVisible(WindowInsetsCompat.Type.ime()))
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }

        // ── Chips auto-send: one tap asks the question ─────────────────────────
        // Static fallbacks; refreshContextChips() swaps in live values once loaded.
        chipYieldCurve?.setOnClickListener { sendQuery("Analyze the current yield curve health.") }
        chipRecession?.setOnClickListener { sendQuery("What is the current recession risk based on this data?") }
        chipWages?.setOnClickListener { sendQuery("What are the latest real wage trends?") }
        chipRecentUpdates?.setOnClickListener { sendQuery(buildRecentUpdatesQuery()) }
        chipRecentNews?.setOnClickListener { sendQuery(buildRecentNewsQuery()) }

        // Pantrii port U4: start-screen prompt cards — a tap sends (KTD7).
        bindPromptCards(b.llPromptCards, SmartPromptGenerator.starterPrompts())

        refreshContextChips()
        val vm = host().getViewModel()
        vm.getCpiData().observe(viewLifecycleOwner) { refreshContextChips() }
        vm.getEmploymentData().observe(viewLifecycleOwner) { refreshContextChips() }
        vm.getTreasuryData().observe(viewLifecycleOwner) { refreshContextChips() }

        // ── Scroll-to-bottom pill ──────────────────────────────────────────────
        b.pillNewReply.setOnClickListener {
            if (chatAdapter().itemCount > 0)
                rvChat.smoothScrollToPosition(chatAdapter().itemCount - 1)
            b.pillNewReply.visibility = View.GONE
        }
        rvChat.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val bb = binding ?: return
                // AIC-02: only a finger drag can stop following; our own
                // programmatic scrolls (state IDLE) never do.
                if (dy < 0 && rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING)
                    followStream = false
                if (isNearBottom(rv)) {
                    followStream = true
                    bb.pillNewReply.visibility = View.GONE
                }
            }
        })

        // ── Send state: enabled only with text and no request in flight ───────
        refreshSendEnabled()
        etMsg.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) { refreshSendEnabled() }
            override fun afterTextChanged(s: Editable?) {}
        })
        etMsg.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendCurrentMessage(); true }
            else false
        }
        // AIC-10: Enter sends from a hardware keyboard; Shift+Enter is a newline.
        etMsg.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                return@setOnKeyListener false
            if (event.isShiftPressed) {
                if (event.action == KeyEvent.ACTION_DOWN)
                    etMsg.text.replace(etMsg.selectionStart.coerceAtLeast(0),
                        etMsg.selectionEnd.coerceAtLeast(0), "\n")
                return@setOnKeyListener true
            }
            if (event.action == KeyEvent.ACTION_DOWN && btnSend.isEnabled && !awaitingResponse)
                sendCurrentMessage()
            true
        }

        btnSend.setOnClickListener {
            if (awaitingResponse) cancelCurrentCall()
            else sendCurrentMessage()
        }

        // ── Attach image (vision analysis) ─────────────────────────────────────
        b.btnAttach.setOnClickListener { pickImageLauncher.launch("image/*") }
        b.btnRemoveImage.setOnClickListener { clearPendingImage() }

        btnClose?.setOnClickListener { close() }

        // TICKET-P2-8: the header identity block is the marker's tap target.
        b.llAnalystIdentity.setOnClickListener { AnalystIdentity.showAbout(requireContext()) }

        b.btnClearChat.setOnClickListener { confirmNewChat() }
        // U3: same spring press as the FAB and KPI badges.
        PressFeedback.attach(b.btnClose)
        PressFeedback.attach(b.btnClearChat)

        // ── Prefill / shared-image entry points ────────────────────────────────
        val args = arguments
        if (savedInstanceState == null && args != null) {
            screenContext = args.getString(ARG_SCREEN_CONTEXT, "")
            conciseNextTurn = args.getBoolean(ARG_CONCISE, false)
            args.remove(ARG_CONCISE)
            val imageUriStr = args.getString(ARG_IMAGE_URI)
            val prefill = args.getString(ARG_PREFILL)
            args.remove(ARG_PREFILL)
            args.remove(ARG_IMAGE_URI)

            if (imageUriStr != null) {
                // Encode off-thread, then auto-send with the prefill (or a default).
                val q = if (prefill != null && !prefill.trim().isEmpty())
                    prefill.trim()
                else
                    "Analyze this image in the context of the current U.S. economic data. " +
                        "If it shows a headline or chart, explain what it means and how it " +
                        "relates to the dashboard's readings."
                attachImage(Uri.parse(imageUriStr), true)
                // attachImage(=autoSend) sends q once encoding finishes
                pendingAutoSendQuery = q
            } else if (prefill != null && !prefill.trim().isEmpty()) {
                val q = prefill.trim()
                view.post { sendQuery(q) }
            }
        }
    }

    /** Loads + encodes an image on a background thread and shows the preview strip. */
    private fun attachImage(uri: Uri, autoSend: Boolean) {
        val appCtx = requireContext().applicationContext
        AppExecutors.diskIO().execute {
            val img = ImageUtils.encode(appCtx, uri)
            runOnUi {
                val b = binding ?: return@runOnUi
                if (img == null) {
                    android.widget.Toast.makeText(requireContext(),
                        "Couldn't read that image.", android.widget.Toast.LENGTH_SHORT).show()
                    return@runOnUi
                }
                pendingImage = img
                b.ivImagePreview.setImageBitmap(img.preview)
                b.llImagePreview.visibility = View.VISIBLE
                refreshSendEnabled()
                val queued = pendingAutoSendQuery
                if (autoSend && queued != null) {
                    pendingAutoSendQuery = null
                    sendQuery(queued)
                }
            }
        }
    }

    private fun clearPendingImage() {
        pendingImage = null
        val b = binding
        if (b != null) {
            b.llImagePreview.visibility = View.GONE
            b.ivImagePreview.setImageDrawable(null)
            refreshSendEnabled()
        }
    }

    /**
     * Entry point for external and gesture callers (AI Law 12: every door
     * behaves the same):
     * carries a screen-context block and the concise flag into an already-open
     * sheet, matching what newInstanceForGesture does for a fresh one.
     */
    fun submitExternalQuery(query: String, screenContextBlock: String?, concise: Boolean) {
        if (screenContextBlock != null && !screenContextBlock.isEmpty())
            screenContext = screenContextBlock
        conciseNextTurn = concise
        if (binding == null) {
            // View not created yet — stash as prefill
            val args = arguments ?: Bundle()
            args.putString(ARG_PREFILL, query)
            if (screenContextBlock != null && !screenContextBlock.isEmpty())
                args.putString(ARG_SCREEN_CONTEXT, screenContextBlock)
            args.putBoolean(ARG_CONCISE, concise)
            arguments = args
            return
        }
        sendQuery(query)
    }

    /**
     * Rewrites the quick-ask chips with live readings so they demo the data
     * ("CPI at 2.6% — is that good?" beats a generic label). Falls back to
     * the static text set in the layout when a series hasn't loaded yet.
     */
    private fun refreshContextChips() {
        val b = binding ?: return
        if (activity == null) return
        val vm = host().getViewModel()

        val cpiList = vm.getCpiData().getValue()
        val cpiRows = if (cpiList != null)
            EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") else null
        if (cpiRows != null && cpiRows.size >= 13) {
            val base = cpiRows[cpiRows.size - 13].value
            if (Math.abs(base) > 1e-9) {
                val yoy = ((cpiRows[cpiRows.size - 1].value - base) / base) * 100.0
                b.chipWages.text = String.format(Locale.US,
                    "CPI at %.1f%% — is that good?", yoy)
                b.chipWages.setOnClickListener {
                    sendQuery(String.format(Locale.US,
                        "CPI inflation just came in at %.2f%% year-over-year. Is that good or bad, " +
                            "how does it compare historically, and are real wages keeping up?", yoy))
                }
            }
        }

        val unemp = EconomicViewModel.getLatest(
            vm.getEmploymentData().getValue(), "Unemployment Rate")
        if (unemp != null) {
            val u = unemp.value
            b.chipRecession.text = String.format(Locale.US,
                "Unemployment %.1f%% — recession risk?", u)
            b.chipRecession.setOnClickListener {
                sendQuery(String.format(Locale.US,
                    "Unemployment is at %.1f%%. Combined with the rest of the dashboard data, " +
                        "what is the current recession risk?", u))
            }
        }

        val treasury = vm.getTreasuryData().getValue()
        val tenY = EconomicViewModel.getLatest(treasury, "10 Year")
        val threeM = EconomicViewModel.getLatest(treasury, "3 Month")
        if (tenY != null && threeM != null) {
            val spread = tenY.value - threeM.value
            b.chipYieldCurve.text = String.format(Locale.US,
                "10Y–3M at %+.2f%% — healthy?", spread)
            b.chipYieldCurve.setOnClickListener {
                sendQuery(String.format(Locale.US,
                    "The 10Y–3M Treasury spread is at %+.2f%%. What does the current yield curve " +
                        "shape signal about the economy?", spread))
            }
        }
    }

    /** AIC-08: New chat archives the thread (kept in Room), so it is not
     *  destructive and needs no red button. */
    private fun confirmNewChat() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("New chat")
            .setMessage("Start a new chat? The current one is archived.")
            .setPositiveButton("New chat") { _, _ -> host().clearChatHistory(archive = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** AIC-10: the chip rows' visibility as conversation state wants it,
     *  by view id; the IME may hide them on top of that. */
    private val chipWant = HashMap<Int, Int>()

    /** AIC-10: tracked from the window insets. */
    private var imeVisible = false

    /** AIC-10: every write to a chip row's visibility goes through here, so
     *  the keyboard collapse and conversation state never overwrite each other. */
    private fun setChipVis(v: View, vis: Int) {
        chipWant[v.id] = vis
        v.visibility = if (imeVisible) View.GONE else vis
    }

    /** AIC-10: chips collapse while the keyboard is up; a reader at the
     *  bottom stays pinned there as the list shrinks. */
    private fun onImeVisibilityChanged(visible: Boolean) {
        if (visible == imeVisible) return
        imeVisible = visible
        val b = binding ?: return
        for (v in listOf<View>(b.tvAskAbout, b.hsvChips, b.cgFollowUps)) {
            chipWant[v.id]?.let { v.visibility = if (visible) View.GONE else it }
        }
        if (visible && followStream) maybeScrollToBottom(false)
    }

    /** Shows the start screen on a fresh chat. Quick-ask chips show once a
     *  conversation exists, whenever the setting is on — chat history
     *  persists across sessions, so tying them to an empty conversation would
     *  hide them forever. Pantrii port U4: the start screen itself carries
     *  only the title, subtitle and prompt cards, so the chips stay off it. */
    private fun updateConversationStateViews() {
        val b = binding ?: return
        if (activity == null) return
        // AIC-08: the welcome message alone still counts as a fresh chat.
        val adapter = chatAdapter()
        val hasMessages = adapter.itemCount > 1 ||
            (adapter.itemCount == 1 && adapter.messageAt(0)?.text != MainActivity.WELCOME_TEXT)
        // Posted: this runs inside the adapter's own notify dispatch.
        b.rvChat.post { if (activity != null) chatAdapter().refreshHistoryCut(HISTORY_CAP / 2) }
        // MOT-03: play the centre-out reveal each time the empty state appears.
        val showEmpty = !hasMessages
        if (showEmpty && b.llEmptyState.visibility != View.VISIBLE)
            b.llEmptyState.post { CentreOutStagger.animateIn(binding?.llPromptCards) }
        if (!hasMessages) setChipVis(b.cgFollowUps, View.GONE)
        b.llEmptyState.visibility = if (hasMessages) View.GONE else View.VISIBLE
        val chipsEnabled = SettingsManager.getBool(requireContext(),
            SettingsManager.KEY_SMART_CHIPS, true)
        val chipVis = if (chipsEnabled && hasMessages) View.VISIBLE else View.GONE
        setChipVis(b.tvAskAbout, chipVis)
        setChipVis(b.hsvChips, chipVis)
    }

    private fun cancelCurrentCall() {
        // Cancels a queued-but-not-yet-fired auto-retry, not just an in-flight
        // call — currentCall during the retry delay window still points at the
        // already-failed call, so cancelling only that would be a no-op.
        retryHandler.removeCallbacksAndMessages(null)
        currentCall?.cancel()
        // The cancelled call cannot be relied on to fire a callback: during
        // the retry delay window currentCall is an already-failed call whose
        // cancel() is a no-op, and between tool rounds it is a completed one.
        // Restore the terminal UI state here; the callback-side resets stay as
        // an idempotent second pass for the genuinely in-flight case.
        if (isAdded) {
            keepStoppedAnswer()
            requestFinished()
            chatAdapter().removeTypingIndicator()
        }
    }

    /**
     * AIC-03: Stop used to leave the partial answer on screen but unlabelled,
     * unsaved and out of the conversation history, so the next question lost
     * it and a restart erased it. Keep it, mark it incomplete in the text
     * itself (so the label survives Room and the model sees it too), and
     * commit it like a finished answer.
     */
    private fun keepStoppedAnswer() {
        val t = activeTurn.get() ?: return
        if (!activeTurn.compareAndSet(t, null)) return
        val st = t.st
        val partial = stripDanglingAnnouncement(st.acc.toString()).trim()
        if (partial.isEmpty()) return
        val text = partial + ChatMessage.STOPPED_SUFFIX
        commitToHistory(t.query, text)
        val msg = ChatMessage(text, false)
        msg.charts = ArrayList(st.charts)
        if (st.streamIdx >= 0) {
            chatAdapter().updateMessageText(st.streamIdx, text)
            if (msg.charts.isNotEmpty()) chatAdapter().updateMessageCharts(st.streamIdx, msg.charts)
            chatAdapter().messageAt(st.streamIdx)?.let { attachAnswerMeta(it, st) }
        } else {
            attachAnswerMeta(msg, st)
            chatAdapter().addMessage(msg)
        }
        host().persistChatMessage(msg)
        announceAnswer(text)
    }

    /**
     * AIC-06: streamed text is deliberately not a live region (it would read
     * every partial update), so TalkBack users get the finished answer read
     * once here. Markdown markers are dropped so they aren't spoken.
     */
    private fun announceAnswer(text: String) {
        val b = binding ?: return
        val spoken = text.replace(Regex("[*_#`>|]+"), "").replace(Regex("\\s+"), " ").trim()
        if (spoken.isNotEmpty()) b.root.announceForAccessibility(spoken)
    }

    /** AIC-07: error bubbles are read aloud too, not just finished answers. */
    private fun addErrorBubble(text: String) {
        chatAdapter().addMessage(ChatMessage(text, false, false, true))
        announceAnswer(text)
    }

    /** True when no network can reach the internet; safe off the main thread. */
    private fun isOffline(): Boolean {
        val cm = context?.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Send button doubles as Stop while a response is streaming. */
    private fun refreshSendEnabled() {
        val b = binding ?: return
        if (awaitingResponse) {
            b.btnSend.setImageResource(R.drawable.ic_stop)
            b.btnSend.contentDescription = "Stop response"
            b.btnSend.isEnabled = true
            b.btnSend.alpha = 1f
        } else {
            b.btnSend.setImageResource(R.drawable.ic_send_filled)
            b.btnSend.contentDescription = "Send message"
            val canSend = b.etMessage.text.toString().trim().length > 0 || pendingImage != null
            b.btnSend.isEnabled = canSend
            b.btnSend.alpha = if (canSend) 1f else 0.4f
        }
    }

    private fun sendCurrentMessage() {
        val b = binding ?: return
        var text = b.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingImage != null)
            text = "Analyze this image in the context of the current economic data."
        sendQuery(text)
    }

    /** Single entry point for user-visible questions (input, chips, prompts, prefill). */
    private fun sendQuery(userQuery: String?) {
        val b = binding ?: return
        if (awaitingResponse) return
        if (userQuery == null || userQuery.trim().isEmpty()) return
        val q = userQuery.trim()
        setChipVis(b.cgFollowUps, View.GONE) // MOT-03: follow-ups belong to the last answer
        b.etMessage.setText("")
        b.rvChat.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        val userMsg = ChatMessage(q, true)
        pendingImage?.let { userMsg.imageBitmap = it.preview }
        chatAdapter().addMessage(userMsg)
        host().persistChatMessage(userMsg)
        maybeScrollToBottom(true)
        queryClaude(q, b.rvChat)
    }

    /** Re-sends the last question after a tap on an error bubble. */
    fun resendLastQuery() {
        val b = binding ?: return
        if (awaitingResponse) return
        val q = lastUserQuery()
        if (q == null || q.isEmpty()) return
        chatAdapter().removeLastError()
        maybeScrollToBottom(true)
        queryClaude(q, b.rvChat)
    }

    /**
     * AIC-05: asks the last question again. The old answer leaves the screen,
     * the history and Room, and rides along as a variant of the new one, so
     * the "1/2" toggle can bring it back.
     */
    private fun regenerate() {
        val b = binding ?: return
        if (awaitingResponse) return
        val q = chatAdapter().questionFor(chatAdapter().lastAnswerIndex())
        if (q.isNullOrEmpty()) return
        val old = chatAdapter().removeLastAnswer() ?: return
        pendingVariants = if (old.variants.isEmpty())
            listOf(ChatMessage.AnswerVariant(old.text, old.charts, old.modelLabel))
        else ArrayList(old.variants)
        val history = conversationHistory()
        synchronized(history) {
            val n = history.length()
            if (n >= 2 && history.optJSONObject(n - 1)?.optString("role") == "assistant") {
                history.remove(n - 1)
                history.remove(n - 2)
            }
        }
        host().deletePersistedLastAnswer()
        followStream = true
        maybeScrollToBottom(true)
        queryClaude(q, b.rvChat)
    }

    /** AIC-05: label + variants for an answer this turn produced. */
    private fun attachAnswerMeta(m: ChatMessage, st: StreamState) {
        m.modelLabel = ModelConfig.label(st.lastModel)
        if (st.priorVariants.isEmpty()) return
        m.variants.clear()
        m.variants.addAll(st.priorVariants)
        m.variants.add(ChatMessage.AnswerVariant(m.text, ArrayList(m.charts), m.modelLabel))
        m.variantIndex = m.variants.size - 1
    }

    /** AIC-05: the history's last answer follows the variant on screen, so
     *  the next question builds on what the user chose to keep. */
    private fun syncHistoryToVariant(text: String?) {
        if (text == null) return
        val history = conversationHistory()
        synchronized(history) {
            val last = history.optJSONObject(history.length() - 1) ?: return
            if (last.optString("role") == "assistant") last.put("content", text)
        }
    }

    private val turnActions = object : ChatAdapter.TurnActions {
        override fun isBusy(): Boolean = awaitingResponse
        override fun onRegenerate() = regenerate()
        override fun onStartNewChat() = confirmNewChat()
        override fun onContinue() = sendQuery(CONTINUE_PROMPT)
        override fun onVariantShown(text: String?) = syncHistoryToVariant(text)
    }

    /** Terminal state of a request: allow sending again. */
    private fun requestFinished() {
        awaitingResponse = false
        refreshSendEnabled()
        if (activity != null) chatAdapter().refreshActionRows() // AIC-05
        // MOT-02 backstop: every exit path (success, error, cancel) lands here,
        // so the trace can never be left "Thinking". Success paths finish it
        // first with "Thought"; anything still running here was cut short.
        if (activity != null) {
            chatAdapter().finishTrace("Stopped")
            // MOT-05: same backstop for the step list — a running step was cut
            // short and becomes failed. No-op after a successful close.
            chatAdapter().closeTraceTurn(false)
        }
    }

    /**
     * MOT-05: closes the turn's step list after a successful answer, and fires
     * the one confirm haptic for a multi-step turn. Fired here, not when a
     * step ticks: the last step is only knowable once the turn is over.
     */
    private fun closeTurnSuccessfully() {
        if (chatAdapter().closeTraceTurn(true)) HapticUtil.confirm(binding?.rvChat)
    }

    /** MOT-05: posts a step's state (and duration) from the tool thread. */
    private fun markStep(id: String, state: TraceStep.State, durationMs: Long = -1L) {
        runOnUi {
            if (binding == null) return@runOnUi
            chatAdapter().updateTraceStep(id, state, durationMs = durationMs)
        }
    }

    /** A streamed tool call's accumulated input; `{}` when malformed. */
    private fun parseToolInput(tb: ToolUseBlock): JSONObject =
        try { JSONObject(tb.inputJson.toString()) } catch (e: Exception) { JSONObject() }

    /**
     * AIC-02: a send ([force]) always jumps to the very bottom and resumes
     * following. While a reply streams, the list follows unless the user has
     * dragged up; then the "New reply" pill shows instead.
     *
     * "Bottom" is measured in pixels: the old item-count test treated a tall
     * answer whose top was on screen as "at the bottom", and scrollToPosition
     * on an already-visible item does nothing, so the growing reply and the
     * user's own question stayed off screen.
     */
    private fun maybeScrollToBottom(force: Boolean) {
        val b = binding ?: return
        val rv = b.rvChat
        if (chatAdapter().itemCount == 0) return
        if (force) followStream = true
        if (followStream) {
            b.pillNewReply.visibility = View.GONE
            scrollToAbsoluteBottom(rv)
        } else {
            b.pillNewReply.visibility = View.VISIBLE
        }
    }

    /** Scrolls past the end of the last item, after the pending layout lands. */
    private fun scrollToAbsoluteBottom(rv: RecyclerView) {
        rv.post {
            val count = chatAdapter().itemCount
            if (binding == null || count == 0) return@post
            rv.scrollToPosition(count - 1)
            rv.post {
                if (binding == null) return@post
                val remaining = rv.computeVerticalScrollRange() -
                    rv.computeVerticalScrollExtent() - rv.computeVerticalScrollOffset()
                if (remaining > 0) rv.scrollBy(0, remaining)
            }
        }
    }

    private fun isNearBottom(rv: RecyclerView): Boolean {
        val slop = FOLLOW_SLOP_DP * rv.resources.displayMetrics.density
        val remaining = rv.computeVerticalScrollRange() -
            rv.computeVerticalScrollExtent() - rv.computeVerticalScrollOffset()
        return remaining <= slop
    }


    private fun queryClaude(userQuery: String, rv: RecyclerView) {
        if (ApiConfig.PROXY_BASE_URL.isEmpty()) {
            chatAdapter().addMessage(ChatMessage(
                "Econ Analyst is not configured. Deploy the proxy in the proxy/ folder, " +
                    "then set PROXY_BASE_URL in local.properties (see proxy/README.md).", false))
            return
        }

        setLastUserQuery(userQuery)
        awaitingResponse = true
        refreshSendEnabled()
        chatAdapter().refreshActionRows() // AIC-05: older answers lose Regenerate
        // MOT-02: a timed "Thinking" trace replaces the anonymous typing dots.
        chatAdapter().addTrace(System.currentTimeMillis())
        maybeScrollToBottom(false)

        try {
            val cachedNews: List<NewsItem> = NewsRepository.getInstance().cachedItems

            // Cap context at the most recent HISTORY_CAP entries to keep
            // request latency flat in long conversations.
            val messages = JSONArray()
            val history = conversationHistory()
            // History is committed from OkHttp background threads; lock while
            // slicing so a concurrent commit can't interleave.
            synchronized(history) {
                val start = Math.max(0, history.length() - HISTORY_CAP)
                for (i in start until history.length())
                    messages.put(history.get(i))
            }

            val enrichedQuery = userQuery + NewsContextBuilder.buildBrief(cachedNews)
            val userMsg = JSONObject()
            userMsg.put("role", "user")

            // Vision: attach the pending image as a base64 content block.
            val img = pendingImage
            if (img != null) {
                val content = JSONArray()
                val imageBlock = JSONObject()
                    .put("type", "image")
                    .put("source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", img.mediaType)
                        .put("data", img.base64))
                content.put(imageBlock)
                content.put(JSONObject().put("type", "text").put("text", enrichedQuery))
                userMsg.put("content", content)
                clearPendingImage()
            } else {
                userMsg.put("content", enrichedQuery)
            }
            messages.put(userMsg)

            // Consume the gesture flag so typed follow-ups get full length.
            val conciseTurn = conciseNextTurn
            conciseNextTurn = false
            val systemPrompt = buildSystemPrompt(cachedNews, conciseTurn)
            // AI Law 6: gesture turns and tool-likely questions route to the
            // stronger model for the whole turn; plain questions stay on Haiku.
            val st = StreamState()
            st.routeStrong = conciseTurn || looksToolLikely(userQuery)
            st.gestureTurn = conciseTurn
            st.priorVariants = pendingVariants
            pendingVariants = emptyList()
            activeTurn.set(ActiveTurn(userQuery, st))
            sendTurn(messages, systemPrompt, userQuery, st, 0)
        } catch (e: Exception) {
            requestFinished()
            chatAdapter().removeTypingIndicator()
            chatAdapter().addMessage(ChatMessage("Error: " + e.message, false))
        }
    }

    /**
     * The system prompt split at the prompt-caching breakpoint (TICKET-E /
     * review F5/Q14). `fixed` is the instructional rules text — byte-
     * identical for every user, turn, and session until the developer edits
     * the prompt — and is marked ephemeral-cacheable in sendTurn()'s request
     * body. `rest` carries everything that varies per turn or per round
     * (length tier, screen context, live data snapshot, derived metrics,
     * release calendar, historical context, news).
     */
    private class SystemPromptParts(@JvmField val fixed: String, @JvmField val rest: String)

    private fun buildSystemPrompt(cachedNews: List<NewsItem>, conciseGesture: Boolean): SystemPromptParts {
        val dataSnapshot = constructEconomicContext()

        // Settings: response length preference. AI Law 7: a one-tap gesture is
        // a glance, not a research request — it gets the tightest tier.
        // Lives with the other turn-volatile blocks below (not spliced into
        // the fixed rules text) so that text stays one contiguous, cacheable
        // block — see `fixed` below.
        // TICKET-P2-4: the gesture tier is a truncated pyramid, not a
        // truncation — the reply carries only what was asked plus accuracy
        // caveats; deferred detail surfaces as follow-up chips after the
        // answer (showFollowUpPrompts), so the prompt can honestly promise it.
        val lengthRule = if (conciseGesture)
            "This question came from a one-tap gesture on a card or chart. Answer in " +
                "3 sentences or fewer; the card carries the numbers. Say only what was " +
                "asked, plus any caveat needed to keep it accurate — never drop an " +
                "accuracy caveat to save words. Lead with the answer in the first " +
                "sentence. Skip news and upcoming releases unless directly relevant. " +
                "Deeper detail is offered to the user separately as follow-up " +
                "questions, so leave it out rather than compressing it in. "
        else if (SettingsManager.getBool(requireContext(), SettingsManager.KEY_DETAILED_AI, false))
            // The one opt-out from the 3-sentence rule (decided 2026-10-12).
            "Detailed analysis is welcome — up to roughly 500 words when the question " +
                "warrants it. Still lead with the answer in the first sentence. "
        else
            "Answer in 3 sentences or fewer; the card carries the numbers — when a " +
                "chart or table would show the figures, draw it rather than listing " +
                "them. Go longer only if the user explicitly asks for detail. "
        val newsContext = NewsContextBuilder.build(cachedNews)
        val derived = DerivedMetricsBuilder.build(host().getViewModel())
        val releases = ReleaseCalendar.build()

        var screenBlock = ""
        val tabLabel = host().getCurrentScreenLabel()
        if (tabLabel != null && !tabLabel.isEmpty())
            screenBlock += "\nUSER CONTEXT: The user is currently on the " + tabLabel +
                " screen of the app.\n"
        if (screenContext != null && !screenContext.isEmpty())
            screenBlock += screenContext + "\n"

        val fixed = "You are the Econ Analyst, an economic analyst embedded in the U.S. Economic " +
            "Monitor app. " +
            "You have access to the following live data pulled directly from the app, " +
            "plus 24 months of locally cached historical data (treasury yields, mortgage " +
            "rates, unemployment, GDP). Always use these exact figures when answering " +
            "questions — never substitute general knowledge for values that appear in " +
            "the data below. Use the historical series to discuss trends, compare current " +
            "values to past readings, and answer questions about specific past months. " +
            "If a value is listed as 'Unavailable' it has not yet been fetched. Any value " +
            "tagged [STALE...] may be out of date — if you cite it, say it may not be " +
            "current.\n\n" +
            "TOOLS: You can call get_series for point-by-point history of any series in " +
            "the tool's options — this covers the cached series (treasury yields, " +
            "mortgage rate, unemployment, GDP) and the live dashboard series (CPI, PCE, " +
            "Core PCE, S&P 500, Nasdaq, VIX, wages, housing, credit spreads) — and " +
            "get_headlines for more news than the prompt includes. Use them when the " +
            "summaries below lack the detail a question needs; otherwise answer directly " +
            "from the data provided. If you decide to use a tool, call it immediately in " +
            "the same turn — NEVER end a reply by announcing that you are about to look " +
            "something up. Also never phrase a real, complete answer as if it were still " +
            "pending — if a sentence already states the actual number or conclusion, say " +
            "it plainly instead of wrapping it in lookup language like 'I'll check...' or " +
            "'let me pull...'. If a tool returns 'Invalid series_id', 'No cached data', or " +
            "'No data loaded', do not retry the same call — say plainly what isn't " +
            "available and answer from the snapshot instead.\n\n" +
            "INLINE CHARTS: When a visual trend would genuinely help, call the " +
            "render_chart tool and the app draws the chart inside your reply — one call " +
            "per chart, several charts are fine when you are comparing series. It renders " +
            "for the user and returns no data to you, so never wait on it for numbers, " +
            "never mention the tool or the chart mechanics in your prose, and never write " +
            "any chart markup or tag into your text. Call render_table instead when " +
            "the exact recent readings matter more than the trend shape; it shows the " +
            "last few months, newest first, with each month's change. Each call's result " +
            "is the one-line summary the card shows — quote those figures, not others.\n\n" +
            "FORMATTING RULES: Do NOT use markdown headers (##, ###, or # prefix). " +
            "Do NOT use bullet lists with dashes or asterisks. " +
            "Use short paragraphs and bold text (**word**) only for key figures or labels. " +
            "Lead with the conclusion: your first sentence should answer the question; " +
            "context and caveats follow. " +
            "When the user quotes or references a specific news headline, treat that headline as a real, " +
            "current article and analyze it directly. NEVER reply that a headline is not in your cached feed — " +
            "the news list below is only a partial sample, not the full set of articles the user can tap. " +
            "When news headlines are provided, weave relevant recent developments into your analysis where appropriate. " +
            "When an upcoming data release is relevant, mention it so the user knows what to watch. " +
            "If a request is outside what you can do, say so plainly in your first sentence — " +
            "never pad the refusal, and never bury the part you can help with behind the part you can't.\n"

        val rest = lengthRule +
            screenBlock + "\n" +
            dataSnapshot + derived + releases + historicalContext + newsContext

        return SystemPromptParts(fixed, rest)
    }

    /**
     * Sends one request round. If Claude stops to call tools, the tools are
     * executed on-device and the loop continues (up to MAX_TOOL_DEPTH rounds),
     * streaming all text into a single analyst bubble.
     */
    private fun sendTurn(
        messages: JSONArray, systemPrompt: SystemPromptParts, committedQuery: String,
        st: StreamState, depth: Int
    ) {
        try {
            val body = JSONObject()
            // AI Law 6: model picked per round by the routing method, not a literal.
            val model = chooseModel(st, depth)
            st.lastModel = model
            body.put("model", model)
            // 1024 truncated longer answers (and could cut tool-call JSON
            // mid-stream, yielding empty tool inputs). Each round gets its own
            // budget, so a bigger cap only costs tokens actually generated.
            body.put("max_tokens", 4096)
            body.put("stream", true)
            // Sent as a 2-block array (not a bare string) so the fixed rules
            // prefix can carry an ephemeral cache_control breakpoint — it's
            // byte-identical on every round of this loop (up to MAX_TOOL_DEPTH
            // rounds) and across turns in the same session, so the Anthropic
            // API can serve it from cache after the first round instead of
            // re-billing it every time (TICKET-E / review F5/Q14).
            val systemBlocks = JSONArray()
            // Known limitation (AI Law 18): tools + fixed measure well under
            // Haiku 4.5's 4,096-token minimum cacheable prefix, so on the fast
            // model THIS breakpoint alone is decorative — under-minimum
            // prefixes fail silently. It still serves Sonnet (1,024 minimum).
            systemBlocks.put(JSONObject()
                .put("type", "text")
                .put("text", systemPrompt.fixed)
                .put("cache_control", JSONObject().put("type", "ephemeral")))
            // Second breakpoint on the turn-volatile block (TICKET-P2-1). The
            // same SystemPromptParts object is reused for every round of one
            // tool loop, and the data snapshot / history / news it carries are
            // byte-stable between refreshes — so rounds 2..MAX_TOOL_DEPTH and
            // back-to-back turns read tools+fixed+rest from cache instead of
            // re-billing the largest part of the prompt. The combined prefix
            // also clears Haiku's 4,096 floor, which the fixed block alone
            // cannot. Verify with usage.cache_read_input_tokens (logged in
            // handleStreamedResponse), never by assuming.
            systemBlocks.put(JSONObject()
                .put("type", "text")
                .put("text", systemPrompt.rest)
                .put("cache_control", JSONObject().put("type", "ephemeral")))
            body.put("system", systemBlocks)
            body.put("messages", messages)
            // Always send tools: the API rejects a request whose message history
            // contains tool_use/tool_result blocks but has no tools parameter,
            // which used to 400 on the final round of a tool loop.
            //
            // Final allowed round (TICKET-P2-2 / R5): data lookups are done, but
            // a chart should still be reachable — the answer that most wants a
            // chart is the one that just fetched history. tool_choice has no
            // allowlist form (auto/any/tool/none; "tool" would FORCE a chart),
            // so the narrowing happens in the tools array itself: render_chart
            // alone, tool_choice left at auto. Replaces the old blanket
            // tool_choice:none, which also silenced charts. Costs a cache miss
            // on this one round (tools head the cached prefix) — accepted,
            // it's the round with the least reusable context. The chart-round
            // cap in handleStreamedResponse keeps this from looping.
            val chartOnly = depth >= MAX_TOOL_DEPTH - 1
            body.put("tools", AnalystToolExecutor.buildToolsJson(chartOnly))
            // TICKET-P2-6 (AI Laws 2, 1): on the first round of a tool-likely
            // turn, force the tool call structurally. tool_choice "any"
            // prefills the assistant turn, so the model emits NO prose before
            // its tool_use blocks — "Let me pull the history…" never exists to
            // be guarded against. Depth-0 only: later rounds have the data in
            // context, and forcing more tools there recreates the free-round
            // hazard P2-2 capped. Full tools stay offered, so the model may
            // satisfy the force with render_chart instead of a data fetch —
            // accepted and MEASURED (see the forced-round log in
            // handleStreamedResponse) before any widening; the chart ack round
            // continues on "auto" and can still fetch. Cache: our breakpoints
            // sit on tools+system, ahead of the messages, so a per-round
            // tool_choice difference should not touch the prefix cache —
            // verify via cache_read_input_tokens, never assume (AI Law 18).
            val forcedRound = depth == 0 && looksToolLikely(committedQuery)
            if (forcedRound)
                body.put("tool_choice", JSONObject().put("type", "any"))

            // Calls the proxy (see proxy/README.md) — the Anthropic key stays
            // server-side and is never shipped inside the APK.
            // Modern OkHttp extension form, not RequestBody.create(MediaType.parse(...)),
            // which can fail to resolve from Kotlin (migration lesson 3).
            val request = Request.Builder()
                .url(ApiConfig.PROXY_BASE_URL + "/v1/messages")
                .addHeader("x-app-token", ApiConfig.PROXY_APP_TOKEN)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val retried = AtomicBoolean(false)
            // Text accumulated before this round; a mid-stream retry rewinds here.
            val accLenAtSend = st.acc.length

            val apiCall = httpClient().newCall(request)
            currentCall = apiCall
            apiCall.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Field diagnosis: the visible errors say what the user saw,
                    // not what actually broke — this is the only record of that.
                    Log.w(TAG, "onFailure depth=$depth retries=${st.retryAttempts}", e)
                    if (call.isCanceled()) {
                        runOnUi {
                            requestFinished()
                            chatAdapter().removeTypingIndicator()
                        }
                        return
                    }
                    // Mobile networks flake — retry once automatically before surfacing,
                    // bounded by MAX_RETRIES_PER_TURN across the whole turn (not just
                    // this invocation) so a persistent failure can't loop forever.
                    if (st.retryAttempts < MAX_RETRIES_PER_TURN && retried.compareAndSet(false, true)) {
                        st.retryAttempts++
                        val self = this
                        retryHandler.postDelayed({
                            // The sheet may have been dismissed during the delay —
                            // httpClient()/host() calls requireActivity(), which throws
                            // if the fragment is detached.
                            if (!isAdded) return@postDelayed
                            val retryCall = httpClient().newCall(request)
                            currentCall = retryCall
                            retryCall.enqueue(self)
                        }, 1500)
                        return
                    }
                    Log.w(TAG, "request failed after retry", e)
                    val plain = plainError(null, e, isOffline())
                    runOnUi {
                        requestFinished()
                        chatAdapter().removeTypingIndicator()
                        addErrorBubble(plain)
                        maybeScrollToBottom(false)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            if (r.isSuccessful) {
                                // Route by BODY shape, not the content-type header:
                                // middleboxes (VPNs, DNS filters) rewrite the header
                                // in the field, and the header-based check sent real
                                // SSE bodies into the JSON fallback — which cannot
                                // represent tool_use rounds, so every forced tool
                                // round died as "empty SSE payload" (2026-08-18
                                // device logs). An Anthropic JSON body always opens
                                // with '{'; an SSE body opens with "event:"/"data:".
                                val peek = r.body?.source()?.peek()
                                val firstByte = try {
                                    if (peek != null && peek.request(1)) peek.buffer[0].toInt() else 0
                                } catch (e: Exception) { 0 }
                                val ct = r.header("content-type", "") ?: ""
                                Log.d(TAG, "response route: content-type=$ct first=$firstByte")
                                if (firstByte != '{'.code) {
                                    handleStreamedResponse(r, committedQuery, call, messages, systemPrompt, st, depth)
                                } else {
                                    handleJsonResponse(r, committedQuery, messages, systemPrompt, st, depth)
                                }
                            } else {
                                val apiMsg = readApiError(r)
                                Log.w(TAG, apiMsg)
                                val plain = plainError(r.code, IOException(apiMsg), false)
                                runOnUi {
                                    requestFinished()
                                    chatAdapter().removeTypingIndicator()
                                    addErrorBubble(plain)
                                    maybeScrollToBottom(false)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "round failed depth=$depth retries=${st.retryAttempts}" +
                            " canceled=${call.isCanceled()}", e)
                        // Streams that die partway through (timeout, dropped
                        // connection, transient server error) get one automatic
                        // retry of this round. Rewind the accumulated text so the
                        // re-streamed round doesn't duplicate what already showed.
                        // A read timeout costs a full 60s window per attempt, so
                        // burning the whole shared retry budget on timeouts makes
                        // the user wait minutes before seeing the error — one
                        // timeout retry, then surface it.
                        val retryCap = if (e is java.net.SocketTimeoutException) 1
                            else MAX_RETRIES_PER_TURN
                        if (e is IOException && !call.isCanceled()
                            && isTransientFailure(e)
                            && st.retryAttempts < retryCap
                            && retried.compareAndSet(false, true)
                        ) {
                            st.retryAttempts++
                            st.acc.setLength(accLenAtSend)
                            // MOT-05: the retry re-announces this round's calls
                            // under new ids; drop the never-run originals.
                            runOnUi { if (binding != null) chatAdapter().dropPendingTraceSteps() }
                            retryHandler.postDelayed(
                                { if (isAdded) sendTurn(messages, systemPrompt, committedQuery, st, depth) },
                                1200)
                            return
                        }
                        val friendly = plainError(null, e, isOffline())
                        runOnUi {
                            requestFinished()
                            chatAdapter().removeTypingIndicator()
                            // Repaint the bubble from acc so a half-streamed
                            // round doesn't linger next to the error.
                            if (st.streamIdx >= 0)
                                chatAdapter().updateMessageText(st.streamIdx, st.acc.toString())
                            if (!call.isCanceled()) addErrorBubble(friendly)
                            maybeScrollToBottom(false)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUi {
                requestFinished()
                chatAdapter().removeTypingIndicator()
                chatAdapter().addMessage(ChatMessage("Error: " + e.message, false))
            }
        }
    }

    /** One accumulating tool_use block within a streamed response. */
    private class ToolUseBlock {
        // optString() returns "" rather than null for a missing key, so these are
        // non-null in practice and declared that way to avoid a spurious fallback.
        @JvmField var id: String = ""
        @JvmField var name: String = ""
        @JvmField val inputJson = StringBuilder()
    }

    /**
     * Reads Anthropic SSE events, appending text deltas to a live bubble.
     * Also accumulates tool_use blocks; when the message stops for tool use,
     * runs the tools locally and continues the conversation.
     */
    private fun handleStreamedResponse(
        r: Response, committedQuery: String, call: Call,
        messages: JSONArray, systemPrompt: SystemPromptParts,
        st: StreamState, depth: Int
    ) {
        val responseBody = r.body ?: throw IOException("empty body")
        val source = responseBody.source()
        // acc may already hold earlier rounds' text; only text streamed in THIS
        // round belongs in this round's assistant message.
        val roundStart = st.acc.length
        // T4: text streamed before this round's first tool call. It is held
        // back from the bubble (the trace row is the progress signal) but
        // still sent to the API as this round's assistant text.
        val held = StringBuilder()

        val toolBlocks = LinkedHashMap<Int, ToolUseBlock>()
        var stopReason: String? = null
        var sawMessageStop = false

        while (true) {
            val line = readLineSafe(source, call) ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.substring(5).trim()
            if (payload.isEmpty()) continue
            val evt: JSONObject
            try { evt = JSONObject(payload) }
            catch (malformed: Exception) { continue } // keep-alives / non-JSON lines like "[DONE]"
            val type = evt.optString("type")
            if ("message_start" == type) {
                // AI Law 18 verification hook (TICKET-P2-1): cache behavior is
                // invisible without this — under-minimum prefixes fail with no
                // error, so the only ground truth is the usage block. Expect
                // cache_read > 0 on round 2+ of a tool loop and on repeat turns
                // within the 5-minute TTL; persistent zeros mean a silent
                // invalidator or a prefix below the model's floor.
                val usage = evt.optJSONObject("message")?.optJSONObject("usage")
                if (usage != null) Log.d(TAG, "usage: model=" + chooseModel(st, depth) +
                    " depth=" + depth +
                    " input=" + usage.optInt("input_tokens") +
                    " cache_read=" + usage.optInt("cache_read_input_tokens") +
                    " cache_write=" + usage.optInt("cache_creation_input_tokens"))
            } else if ("content_block_start" == type) {
                val block = evt.optJSONObject("content_block")
                if (block != null && "tool_use" == block.optString("type")) {
                    if (toolBlocks.isEmpty()) holdBackRoundText(st, roundStart, held)
                    val tb = ToolUseBlock()
                    tb.id = block.optString("id")
                    tb.name = block.optString("name")
                    toolBlocks[evt.optInt("index")] = tb
                    // MOT-02/MOT-05: the call becomes a pending step, keyed by
                    // its tool_use id. Its arguments haven't streamed yet, so
                    // the label is name-only until content_block_stop.
                    val id = tb.id
                    val step = AnalystToolExecutor.stepLabel(tb.name, null)
                    runOnUi {
                        if (binding == null) return@runOnUi
                        chatAdapter().addTraceStep(id, step)
                    }
                }
            } else if ("content_block_stop" == type) {
                // MOT-05: the call's input is complete — name the series.
                val tb = toolBlocks[evt.optInt("index")]
                if (tb != null) {
                    val id = tb.id
                    val label = AnalystToolExecutor.stepLabel(tb.name, parseToolInput(tb))
                    runOnUi {
                        if (binding == null) return@runOnUi
                        chatAdapter().updateTraceStep(id, label = label)
                    }
                }
            } else if ("content_block_delta" == type) {
                val delta = evt.optJSONObject("delta") ?: continue
                val deltaType = delta.optString("type")
                if ("input_json_delta" == deltaType) {
                    val tb = toolBlocks[evt.optInt("index")]
                    tb?.inputJson?.append(delta.optString("partial_json", ""))
                    continue
                }
                val text = delta.optString("text", "")
                if (!text.isEmpty()) {
                    synchronized(st.acc) { st.acc.append(text) }
                    if (!st.bubblePosted) {
                        // First text of the turn: show the bubble at once.
                        st.bubblePosted = true
                        val partial = synchronized(st.acc) { st.acc.toString() }
                        runOnUi {
                            if (binding == null) return@runOnUi
                            if (st.streamIdx < 0) {
                                chatAdapter().removeTypingIndicator()
                                val m = ChatMessage(partial, false)
                                m.isStreaming = true
                                m.streamTouchMs = System.currentTimeMillis()
                                chatAdapter().addMessage(m)
                                st.streamIdx = chatAdapter().getLastIndex()
                            } else {
                                chatAdapter().updateMessageText(st.streamIdx, partial, streaming = true)
                            }
                            maybeScrollToBottom(false)
                        }
                    } else {
                        scheduleStreamFlush(st) // AIC-09: one repaint per 50ms window
                    }
                }
            } else if ("message_delta" == type) {
                val delta = evt.optJSONObject("delta")
                if (delta != null) stopReason = delta.optString("stop_reason", stopReason)
            } else if ("message_stop" == type) {
                sawMessageStop = true
                break
            } else if ("error" == type) {
                val err = evt.optJSONObject("error")
                val msg = if (err != null) err.optString("message", "") else ""
                val errType = if (err != null) err.optString("type", "") else ""
                // Include the error type so the retry guard can tell a
                // transient overload from a permanent bad request (AI Law 5).
                throw IOException("Econ Analyst service error" +
                    (if (errType.isEmpty()) "" else " ($errType)") +
                    (if (msg.isEmpty()) "." else ": $msg"))
            }
        }

        // A clean EOF before message_stop / any stop_reason means the proxy or
        // network dropped the stream mid-reply. The text so far LOOKS complete
        // (it often ends right after a "Let me fetch..." lead-in, because the
        // tool-call JSON was next on the wire), but it isn't — throw so the
        // round is retried once instead of committing a cut-off answer.
        if (!sawMessageStop && stopReason == null && !call.isCanceled())
            throw IOException("The stream ended before the response finished.")

        // ── Tool round-trip: run tools on-device, then continue the turn ──────
        // TICKET-P2-2: render_chart is a rendering directive, not a data lookup,
        // so a round whose only tool calls are charts does NOT consume the
        // MAX_TOOL_DEPTH budget (R4/KTD2) — sharing it would make get_series +
        // get_headlines + a chart unrepresentable and silently drop charts on
        // exactly the data-heavy questions that most deserve one. Chart-only
        // rounds have their own cap (MAX_CHART_ROUNDS) instead, because a round
        // that costs no depth would otherwise be a free infinite loop.
        if ("tool_use" == stopReason && !toolBlocks.isEmpty() && !call.isCanceled()) {
            var hasDataTool = false
            for (tb in toolBlocks.values)
                if (!AnalystToolExecutor.isRenderTool(tb.name)) hasDataTool = true
            // TICKET-P2-6 (R4): on a forced round, record which tools satisfied
            // the force. This is the measurement behind two decisions this loop
            // deliberately did not make: whether the model dodges the force by
            // charting instead of fetching, and whether "any" is safe to widen.
            if (depth == 0 && looksToolLikely(committedQuery)) {
                val names = StringBuilder()
                for (tb in toolBlocks.values) {
                    if (names.length > 0) names.append(",")
                    names.append(tb.name)
                }
                Log.d(TAG, "forced_round satisfied by: $names")
            }
            val mayContinue =
                if (hasDataTool) depth < MAX_TOOL_DEPTH - 1
                else st.chartRounds < MAX_CHART_ROUNDS
            val appCtx = appContext
            if (appCtx != null && mayContinue) {
                val assistantContent = JSONArray()
                // Only THIS round's text — resending the whole accumulated reply
                // made the model see itself repeating and re-announce tool calls.
                val roundText = held.toString() + st.acc.substring(roundStart)
                if (!roundText.trim().isEmpty())
                    assistantContent.put(JSONObject().put("type", "text").put("text", roundText))
                val toolResults = JSONArray()
                var chartsChanged = false
                for (tb in toolBlocks.values) {
                    val input = parseToolInput(tb)
                    assistantContent.put(JSONObject()
                        .put("type", "tool_use").put("id", tb.id)
                        .put("name", tb.name).put("input", input))
                    val result: String
                    // MOT-05: running now; timed around the call itself.
                    val stepId = tb.id
                    markStep(stepId, TraceStep.State.RUNNING)
                    val t0 = SystemClock.elapsedRealtime()
                    val failed: Boolean
                    if (AnalystToolExecutor.isRenderTool(tb.name)) {
                        // Intercepted (not routed through execute()) so the
                        // resolved ChatChart survives — the app draws from its
                        // own store; the tool_result is only an acknowledgement.
                        val outcome = AnalystToolExecutor.renderCard(tb.name, input)
                        var chart = outcome.chart
                        var message = outcome.message
                        if (chart != null) {
                            // T4: the tool_result is the card's own summary line,
                            // so the prose quotes the numbers the card shows.
                            val card = ChatSeriesData.resolve(appCtx, viewModelOrNull(),
                                chart.seriesId, chart.months)
                            if (card.points.size < 2) {
                                // The card would hide itself; say so honestly.
                                message = "Nothing was drawn: no data is loaded for " +
                                    chart.seriesId + " yet. Do not retry and do not " +
                                    "mention the card — just continue your answer in prose."
                                chart = null
                            } else {
                                message = "Shown to the user: " + card.summaryLine() +
                                    ". Continue your answer; do not restate the card's " +
                                    "points one by one."
                            }
                        }
                        if (chart != null) { st.charts.add(chart); chartsChanged = true }
                        result = message
                        failed = chart == null
                    } else {
                        // MOT-04: keep what the call fetched for the sources stack.
                        val outcome = AnalystToolExecutor.executeWithSources(appCtx, tb.name, input)
                        st.sources.addAll(outcome.sources)
                        result = outcome.text
                        failed = outcome.failed
                    }
                    markStep(stepId,
                        if (failed) TraceStep.State.FAILED else TraceStep.State.DONE,
                        SystemClock.elapsedRealtime() - t0)
                    toolResults.put(JSONObject()
                        .put("type", "tool_result")
                        .put("tool_use_id", tb.id)
                        .put("content", result))
                }
                messages.put(JSONObject().put("role", "assistant").put("content", assistantContent))
                messages.put(JSONObject().put("role", "user").put("content", toolResults))
                // Draw new charts under the streaming bubble immediately — the
                // prose keeps streaming around them (R12); an unknown series
                // added nothing and degrades to text (R3).
                if (chartsChanged) {
                    val chartsSnapshot = ArrayList(st.charts)
                    runOnUi {
                        if (binding != null && st.streamIdx >= 0)
                            chatAdapter().updateMessageCharts(st.streamIdx, chartsSnapshot)
                    }
                }
                // AI Law 2: if this round's visible text was nothing but a lookup
                // announcement ("Let me pull the history…"), drop it from the
                // displayed reply — the real answer follows. Genuinely analytical
                // lead-ins are kept. (The API still sees the original text above.)
                if (isLookupAnnouncementOnly(roundText)) {
                    // TICKET-P2-6 (R3): greppable fire log — the acceptance is a
                    // MEASURED drop in this guard's fire rate, and a silent
                    // guard can't be measured. Same tag at all three sites.
                    Log.d(TAG, "law1_guard fired: tool-round lead-in dropped (depth=$depth)")
                }
                // T4: no tool round's prose reaches the bubble — the next round
                // answers on its own. The trace row carries progress instead of
                // the old "Checking the data…" status (a deliberate departure
                // from AI Law 11's status line, noted in the analyst-cards plan).
                synchronized(st.acc) { st.acc.setLength(roundStart) }
                // Paragraph break so the next round's text doesn't run into this one.
                if (st.acc.length > 0) st.acc.append("\n\n")
                syncHeldBubble(st)
                if (!hasDataTool) st.chartRounds++
                // Chart-only rounds re-enter at the SAME depth — the budget they
                // are exempt from is the one this parameter carries.
                sendTurn(messages, systemPrompt, committedQuery, st,
                    if (hasDataTool) depth + 1 else depth)
                return
            }
        }

        // Model asked for another tool round past the cap and produced no text —
        // explain that instead of surfacing an empty reply as a failure.
        if ("tool_use" == stopReason && st.acc.length == 0)
            st.acc.append("I ran out of data-lookup rounds for that question — try asking it a bit more specifically.")

        // AI Law 1 code guard: the model announced a lookup, emitted NO tool_use,
        // and ended cleanly with end_turn — the exact "says it'll fetch then ends"
        // glitch. Auto-continue one round so the promised call (or a direct
        // answer) actually happens instead of committing the dangling text.
        if ("end_turn" == stopReason && toolBlocks.isEmpty()
            && depth < MAX_TOOL_DEPTH - 1 && !call.isCanceled()
        ) {
            val roundText = st.acc.substring(roundStart)
            if (isLookupAnnouncementOnly(roundText)) {
                // TICKET-P2-6 (R3): same greppable tag as the other two sites.
                Log.d(TAG, "law1_guard fired: announce-then-stop, auto-continuing (depth=$depth)")
                messages.put(JSONObject().put("role", "assistant").put("content", roundText))
                messages.put(JSONObject().put("role", "user").put("content",
                    "You said you would look that up but did not call a tool. Call the " +
                        "tool now if the series is available; otherwise answer directly " +
                        "from the data in the system prompt. Do not announce lookups — " +
                        "just answer."))
                synchronized(st.acc) { st.acc.setLength(roundStart) }
                syncHeldBubble(st) // T4: no status line; the trace row shows progress
                sendTurn(messages, systemPrompt, committedQuery, st, depth + 1)
                return
            }
        }

        finalizeTurn(committedQuery, call, st)
    }

    /** Commits the finished answer to history + persistence. */
    /**
     * AIC-09: coalesces stream deltas into one text repaint per
     * [STREAM_FLUSH_MS]. The flush reads the whole accumulated text, so no delta
     * is lost; finalizeTurn and Stop write the final text themselves, and a
     * flush that lands after either backs off because the turn is no longer
     * active.
     */
    private fun scheduleStreamFlush(st: StreamState) {
        if (!st.flushScheduled.compareAndSet(false, true)) return
        HandlerCompat.postDelayed(streamHandler, {
            st.flushScheduled.set(false)
            if (binding == null || activity == null || st.streamIdx < 0) return@postDelayed
            if (activeTurn.get()?.st !== st) return@postDelayed
            val partial = synchronized(st.acc) { st.acc.toString() }
            chatAdapter().updateMessageText(st.streamIdx, partial, streaming = true)
            // MOT-05 + AIC-09: text after the last tool round closes the trace.
            chatAdapter().finishTraceIfIdle()
            maybeScrollToBottom(false)
        }, st, STREAM_FLUSH_MS)
    }

    /** T4: the first tool_use of a round moves the round's streamed text out
     *  of [StreamState.acc] into [held] and repaints the bubble without it. */
    private fun holdBackRoundText(st: StreamState, roundStart: Int, held: StringBuilder) {
        synchronized(st.acc) {
            if (st.acc.length <= roundStart) return
            held.append(st.acc, roundStart, st.acc.length)
            st.acc.setLength(roundStart)
        }
        syncHeldBubble(st)
    }

    /** T4: repaints the streaming bubble from [StreamState.acc]. A bubble left
     *  with no text and no charts is removed, so the next text opens a fresh
     *  one — the trace row stays as the only progress signal. */
    private fun syncHeldBubble(st: StreamState) {
        val text = synchronized(st.acc) { st.acc.toString() }
        val drop = text.isBlank() && st.charts.isEmpty()
        // Reset on the stream thread, so the next delta opens a new bubble
        // instead of flushing into the one queued for removal below.
        if (drop) st.bubblePosted = false
        runOnUi {
            if (binding == null || st.streamIdx < 0) return@runOnUi
            cancelStreamFlush(st) // AIC-09: a queued delta must not repaint held text
            if (drop) {
                chatAdapter().removeMessageAt(st.streamIdx)
                st.streamIdx = -1
            } else {
                chatAdapter().updateMessageText(st.streamIdx, text)
            }
        }
    }

    /** AIC-09: drops a queued flush, e.g. before a held-text repaint. */
    private fun cancelStreamFlush(st: StreamState) {
        streamHandler.removeCallbacksAndMessages(st)
        st.flushScheduled.set(false)
    }

    private fun finalizeTurn(committedQuery: String, call: Call?, st: StreamState) {
        // AI Law 1 fallback for rounds the auto-continue guard can't reach
        // (e.g. the forced-text final round): drop a trailing lookup
        // announcement when real content precedes it.
        val accText = st.acc.toString()
        val finalText = stripDanglingAnnouncement(accText)
        // TICKET-P2-6 (R3): same greppable tag as the other two sites.
        if (finalText != accText)
            Log.d(TAG, "law1_guard fired: dangling announcement stripped at finalize")
        if (finalText.isEmpty()) {
            if (call != null && call.isCanceled()) {
                runOnUi {
                    requestFinished()
                    chatAdapter().removeTypingIndicator()
                }
                return
            }
            throw IOException("empty stream")
        }

        // AIC-03: Stop already committed this turn's partial answer.
        if (!claimTurn(st)) return
        commitToHistory(committedQuery, finalText)
        val persisted = ChatMessage(finalText, false)
        // TICKET-P2-2: charts ride the persisted message, not the text — this
        // is what puts them in Room (via ChatMessageEntity.charts) so they
        // survive a restart, which the old in-text tag got for free.
        persisted.charts = ArrayList(st.charts)
        // MOT-04: sources attach only to the finished answer, never mid-stream.
        // Transient — persistChatMessage() ignores them.
        persisted.sources = st.sources.snapshot()
        runOnUi {
            chatAdapter().finishTrace("Thought") // MOT-02: success, not "Stopped"
            closeTurnSuccessfully() // MOT-05
            requestFinished()
            // If no delta ever arrived on-screen (edge case), add the bubble now.
            if (st.streamIdx < 0) {
                chatAdapter().removeTypingIndicator()
                attachAnswerMeta(persisted, st)
                chatAdapter().addMessage(persisted)
            } else {
                chatAdapter().messageAt(st.streamIdx)?.let { attachAnswerMeta(it, st) }
                // Sync the bubble in case a dangling announcement was stripped.
                chatAdapter().updateMessageText(st.streamIdx, finalText)
                chatAdapter().updateMessageCharts(st.streamIdx, persisted.charts)
                chatAdapter().updateMessageSources(st.streamIdx, persisted.sources)
            }
            host().persistChatMessage(persisted)
            // TICKET-P2-4: the gesture tier's deferred detail gets somewhere
            // to go — chips derived from this answer, not from the screen.
            // AIC-11: one follow-up row, built from this answer and its sources.
            showFollowUpPrompts(finalText, persisted.sources) // MOT-03
            maybeScrollToBottom(false)
            announceAnswer(finalText) // AIC-06
        }
    }

    // ── MOT-03: starter / follow-up prompt chips ────────────────────────────────

    /**
     * Fills [group] with up to [MAX_PROMPT_CHIPS] prompt chips. A tap puts the
     * prompt in the composer and leaves the send to the user — it never
     * dispatches a request.
     */
    private fun bindPromptChips(group: ViewGroup, prompts: List<String>) {
        group.removeAllViews()
        val ctx = requireContext()
        val res = resources
        val padH = res.getDimensionPixelSize(R.dimen.space_lg)
        val padV = res.getDimensionPixelSize(R.dimen.space_sm)
        for (prompt in prompts.take(MAX_PROMPT_CHIPS)) {
            val chip = TextView(ctx)
            chip.text = prompt
            chip.setBackgroundResource(R.drawable.bg_chip_outline)
            chip.setPadding(padH, padV, padH, padV)
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                res.getDimension(R.dimen.text_secondary))
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            chip.isClickable = true
            chip.isFocusable = true
            chip.setOnClickListener { fillComposer(prompt) }
            group.addView(chip)
        }
    }

    /** Pantrii port U4 (R2, KTD7): one full-width card per prompt, at most
     *  [MAX_PROMPT_CARDS]. Fewer prompts means fewer cards, never empty
     *  slots. A tap sends the prompt as the user's message. */
    private fun bindPromptCards(group: ViewGroup, prompts: List<String>) {
        group.removeAllViews()
        val inflater = LayoutInflater.from(group.context)
        for (prompt in startCardPrompts(prompts)) {
            val card = inflater.inflate(R.layout.item_prompt_card, group, false)
            card.findViewById<TextView>(R.id.tvPrompt).text = prompt
            card.setOnClickListener { sendQuery(prompt) }
            group.addView(card)
        }
    }

    private fun fillComposer(prompt: String) {
        val b = binding ?: return
        b.etMessage.setText(prompt)
        b.etMessage.setSelection(b.etMessage.text.length)
        b.etMessage.requestFocus()
    }

    /** Follow-ups under every finished answer, revealed centre-out. */
    private fun showFollowUpPrompts(answerText: String, sources: List<TurnSource> = emptyList()) {
        val b = binding ?: return
        if (activity == null) return
        val followUps = SmartPromptGenerator.generateFollowUpsForAnswer(answerText, sources)
        if (followUps.isEmpty()) {
            setChipVis(b.cgFollowUps, View.GONE)
            return
        }
        bindPromptChips(b.cgFollowUps, followUps)
        setChipVis(b.cgFollowUps, View.VISIBLE)
        b.cgFollowUps.post { CentreOutStagger.animateIn(binding?.cgFollowUps) }
    }

    /**
     * Fallback path for responses not labeled text/event-stream.
     * Older proxy deployments forward Anthropic's SSE stream but stamp it
     * "application/json", so sniff the body: JSON object → parse normally;
     * SSE lines → accumulate the text deltas from the raw string.
     */
    private fun handleJsonResponse(
        r: Response, committedQuery: String,
        messages: JSONArray, systemPrompt: SystemPromptParts,
        st: StreamState, depth: Int
    ) {
        val responseBody = r.body?.string() ?: ""
        val trimmed = responseBody.trim()
        var text: String
        if (trimmed.startsWith("{")) {
            val json = JSONObject(trimmed)
            val apiErr = json.optJSONObject("error")
            if (apiErr != null)
                throw IOException("Econ Analyst service error: " + apiErr.optString("message", "unknown error"))
            val content = json.optJSONArray("content") ?: throw IOException("unrecognized response body")

            // Tool use in a non-streamed reply: run tools and continue the turn.
            if ("tool_use" == json.optString("stop_reason") && depth < MAX_TOOL_DEPTH - 1) {
                val appCtx = appContext
                if (appCtx != null) {
                    val toolResults = JSONArray()
                    for (i in 0 until content.length()) {
                        val block = content.getJSONObject(i)
                        if ("text" == block.optString("type"))
                            st.acc.append(block.optString("text"))
                        if ("tool_use" != block.optString("type")) continue
                        // execute() takes a non-null JSONObject, and optJSONObject
                        // can return null for a malformed block. Java passed the
                        // null straight through and relied on execute()'s catch to
                        // turn the NPE into "Tool error: ..."; an empty object
                        // reaches the same error path with a clearer message and is
                        // what the streamed path above already substitutes.
                        val name = block.optString("name")
                        val input = block.optJSONObject("input") ?: JSONObject()
                        // MOT-05: no content_block_start on this path, so the
                        // step is added here, already knowing its input.
                        val stepId = block.optString("id")
                        val label = AnalystToolExecutor.stepLabel(name, input)
                        runOnUi {
                            if (binding == null) return@runOnUi
                            chatAdapter().addTraceStep(stepId, label)
                        }
                        markStep(stepId, TraceStep.State.RUNNING)
                        val t0 = SystemClock.elapsedRealtime()
                        val outcome = AnalystToolExecutor.executeWithSources(appCtx, name, input)
                        markStep(stepId,
                            if (outcome.failed) TraceStep.State.FAILED else TraceStep.State.DONE,
                            SystemClock.elapsedRealtime() - t0)
                        st.sources.addAll(outcome.sources) // MOT-04
                        toolResults.put(JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", block.optString("id"))
                            .put("content", outcome.text))
                    }
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    messages.put(JSONObject().put("role", "user").put("content", toolResults))
                    // Paragraph break so the next round's text doesn't run into this one.
                    if (st.acc.length > 0) st.acc.append("\n\n")
                    sendTurn(messages, systemPrompt, committedQuery, st, depth + 1)
                    return
                }
            }

            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val block = content.getJSONObject(i)
                if ("text" == block.optString("type")) sb.append(block.optString("text"))
            }
            text = sb.toString()
            if (text.isEmpty() && "tool_use" == json.optString("stop_reason"))
                text = "I ran out of data-lookup rounds for that question — try asking it a bit more specifically."
        } else if (trimmed.contains("data:")) {
            text = extractTextFromSseString(trimmed)
            if (text.isEmpty()) throw IOException("empty SSE payload")
        } else {
            throw IOException("unrecognized response body")
        }

        st.acc.append(text.substring(Math.min(text.length,
            if (st.acc.length > 0 && text.startsWith(st.acc.toString())) st.acc.length else 0)))
        if (st.acc.length == 0) st.acc.append(text)

        if (!claimTurn(st)) return // AIC-03: Stop got here first
        commitToHistory(committedQuery, st.acc.toString())
        val msg = ChatMessage(st.acc.toString(), false)
        // Charts collected by earlier streamed rounds of this same turn still
        // belong to the reply even when the final round came back as JSON.
        msg.charts = ArrayList(st.charts)
        msg.sources = st.sources.snapshot() // MOT-04
        runOnUi {
            chatAdapter().finishTrace("Thought") // MOT-02: success, not "Stopped"
            closeTurnSuccessfully() // MOT-05
            requestFinished()
            chatAdapter().removeTypingIndicator()
            // ChatMessage is Kotlin (batch 3), so `text` is a property here —
            // msg.getText() would not resolve from Kotlin.
            if (st.streamIdx >= 0) {
                chatAdapter().updateMessageText(st.streamIdx, msg.text)
                if (msg.charts.isNotEmpty())
                    chatAdapter().updateMessageCharts(st.streamIdx, msg.charts)
                chatAdapter().updateMessageSources(st.streamIdx, msg.sources) // MOT-04
            } else chatAdapter().addMessage(msg)
            host().persistChatMessage(msg)
            maybeScrollToBottom(false)
        }
    }

    private fun commitToHistory(userQuery: String, assistantText: String) {
        val committedUser = JSONObject()
        committedUser.put("role", "user"); committedUser.put("content", userQuery)

        val assistantMsg = JSONObject()
        assistantMsg.put("role", "assistant"); assistantMsg.put("content", assistantText)

        val history = conversationHistory()
        synchronized(history) {
            // Committed as a pair so a HISTORY_CAP slice always starts on a
            // "user" message (the API requires user-first, alternating roles).
            history.put(committedUser)
            history.put(assistantMsg)
        }
    }

    private fun constructEconomicContext(): String {
        val vm = host().getViewModel()
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm z", Locale.US).format(Date())
        sb.append("=== U.S. ECONOMIC MONITOR — LIVE DATA SNAPSHOT ===\n").append("As of: ").append(timestamp).append("\n\n")

        sb.append("CORE INDICATORS\n")
        val fedFunds = EconomicViewModel.getLatest(vm.getFedFundsData().getValue(), "Federal Funds Effective Rate")
        sb.append("Fed Funds Rate: ").append(if (fedFunds != null) String.format(Locale.US, "%.2f%% (%s)", fedFunds.value, fedFunds.date) + freshnessTag(fedFunds.date, 75) else "Unavailable").append("\n")

        val gdpList = vm.getGdpData().getValue()
        val gdpRows = if (gdpList != null) EconomicViewModel.filterBySeries(gdpList, "Gross domestic product") else null
        if (gdpRows != null && !gdpRows.isEmpty()) {
            val start = Math.max(0, gdpRows.size - 4); var sum = 0.0
            for (i in start until gdpRows.size) sum += gdpRows[i].value
            sb.append(String.format(Locale.US, "GDP Growth (4Q Avg): %.2f%%\n", sum / (gdpRows.size - start)))
            val latestGdp = gdpRows[gdpRows.size - 1]
            val qLabel = vm.getLatestQuarterLabel().getValue()
            sb.append(String.format(Locale.US, "GDP Growth (Latest Quarter): %.2f%% (%s)%s\n", latestGdp.value, if (qLabel != null) qLabel else latestGdp.date, freshnessTag(latestGdp.date, 160)))
        } else { sb.append("GDP Growth (4Q Avg): Unavailable\nGDP Growth (Latest Quarter): Unavailable\n") }

        val cpiList = vm.getCpiData().getValue()
        val cpiRows = if (cpiList != null) EconomicViewModel.filterBySeries(cpiList, "CPI-U All Items") else null
        if (cpiRows != null && cpiRows.size >= 13) {
            val yoy = ((cpiRows[cpiRows.size - 1].value - cpiRows[cpiRows.size - 13].value) / cpiRows[cpiRows.size - 13].value) * 100.0
            sb.append(String.format(Locale.US, "CPI-U YoY: %.2f%% (%s)%s\n", yoy, cpiRows[cpiRows.size - 1].date, freshnessTag(cpiRows[cpiRows.size - 1].date, 75)))
        } else { sb.append("CPI-U YoY: Unavailable\n") }

        val unemp = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Unemployment Rate")
        sb.append("Unemployment Rate: ").append(if (unemp != null) String.format(Locale.US, "%.1f%% (%s)", unemp.value, unemp.date) + freshnessTag(unemp.date, 75) else "Unavailable").append("\n")

        val lfpr = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Labor Force Participation Rate")
        sb.append("Labor Force Participation: ").append(if (lfpr != null) String.format(Locale.US, "%.1f%% (%s)", lfpr.value, lfpr.date) + freshnessTag(lfpr.date, 75) else "Unavailable").append("\n")

        val pceList = vm.getPceData().getValue()
        val pceRows = if (pceList != null) EconomicViewModel.filterBySeries(pceList, "PCE Price Index") else null
        if (pceRows != null && pceRows.size >= 13) {
            val yoy = ((pceRows[pceRows.size - 1].value - pceRows[pceRows.size - 13].value) / pceRows[pceRows.size - 13].value) * 100.0
            sb.append(String.format(Locale.US, "PCE Inflation (YoY): %.2f%% (%s)%s\n", yoy, pceRows[pceRows.size - 1].date, freshnessTag(pceRows[pceRows.size - 1].date, 75)))
        } else { sb.append("PCE Inflation (YoY): Unavailable\n") }

        val corePceRows = if (pceList != null) EconomicViewModel.filterBySeries(pceList, "Core PCE Price Index") else null
        if (corePceRows != null && corePceRows.size >= 13) {
            val yoy = ((corePceRows[corePceRows.size - 1].value - corePceRows[corePceRows.size - 13].value) / corePceRows[corePceRows.size - 13].value) * 100.0
            sb.append(String.format(Locale.US, "Core PCE (YoY): %.2f%% (%s)%s\n", yoy, corePceRows[corePceRows.size - 1].date, freshnessTag(corePceRows[corePceRows.size - 1].date, 75)))
        } else { sb.append("Core PCE (YoY): Unavailable\n") }

        val pmi = EconomicViewModel.getLatest(vm.getIsmPmiData().getValue(), "NAPM")
        sb.append("ISM Manufacturing PMI: ").append(if (pmi != null) String.format(Locale.US, "%.1f (%s)", pmi.value, pmi.date) + freshnessTag(pmi.date, 75) else "Unavailable").append("\n")

        val hourly = EconomicViewModel.getLatest(vm.getWageData().getValue(), "Average Hourly Earnings - Private")
        sb.append("Avg Hourly Earnings: ").append(if (hourly != null) String.format(Locale.US, "$%.2f (%s)", hourly.value, hourly.date) + freshnessTag(hourly.date, 75) else "Unavailable").append("\n")

        val wageList = vm.getWageData().getValue()
        val wageRows = if (wageList != null) EconomicViewModel.filterBySeries(wageList, "Average Hourly Earnings - Private") else null
        if (wageRows != null && wageRows.size >= 13 && cpiRows != null && cpiRows.size >= 13) {
            val wYoy = ((wageRows[wageRows.size - 1].value - wageRows[wageRows.size - 13].value) / wageRows[wageRows.size - 13].value) * 100.0
            val cYoy = ((cpiRows[cpiRows.size - 1].value - cpiRows[cpiRows.size - 13].value) / cpiRows[cpiRows.size - 13].value) * 100.0
            val wageDate = wageRows[wageRows.size - 1].date
            sb.append(String.format(Locale.US, "Real Wage Growth (Wages-CPI spread): %.2f%% (%s)%s\n", wYoy - cYoy, wageDate, freshnessTag(wageDate, 75)))
        } else { sb.append("Real Wage Growth (Wages-CPI spread): Unavailable\n") }

        sb.append("\nTREASURY YIELD CURVE\n")
        val treasuryList = vm.getTreasuryData().getValue()
        val maturities = arrayOf("1 Month", "3 Month", "6 Month", "1 Year", "2 Year", "5 Year", "10 Year", "30 Year")
        val shortLabels = arrayOf("1M", "3M", "6M", "1Y", "2Y", "5Y", "10Y", "30Y")
        if (treasuryList != null && !treasuryList.isEmpty()) {
            var curveDate: String? = null
            for (i in maturities.indices) {
                val yld = EconomicViewModel.getLatest(treasuryList, maturities[i])
                if (yld != null && (curveDate == null || yld.date > curveDate)) curveDate = yld.date
                sb.append(shortLabels[i]).append(": ").append(if (yld != null) String.format(Locale.US, "%.2f%%", yld.value) else "N/A")
                sb.append(if (i < maturities.size - 1) "  " else "\n")
            }
            if (curveDate != null) sb.append(String.format(Locale.US, "Curve as of: %s%s\n", curveDate, freshnessTag(curveDate, 10)))
        } else { sb.append("Unavailable\n") }

        val spread10y2y = vm.getCalculatedSpreadData().getValue()
        if (spread10y2y != null && !spread10y2y.isEmpty()) {
            val s = spread10y2y[spread10y2y.size - 1]
            sb.append(String.format(Locale.US, "10Y-2Y Spread: %.2f%%%s (%s)%s\n", s.value, if (s.value < 0) " (INVERTED)" else "", s.date, freshnessTag(s.date, 10)))
        } else { sb.append("10Y-2Y Spread: Unavailable\n") }

        val spread10y3m = vm.getCalculatedSpread3MData().getValue()
        if (spread10y3m != null && !spread10y3m.isEmpty()) {
            val s = spread10y3m[spread10y3m.size - 1]
            sb.append(String.format(Locale.US, "10Y-3M Spread: %.2f%%%s (%s)%s\n", s.value, if (s.value < 0) " (INVERTED)" else "", s.date, freshnessTag(s.date, 10)))
        } else { sb.append("10Y-3M Spread: Unavailable\n") }

        sb.append("\nHOUSING\n")
        val housingList = vm.getHousingData().getValue()
        val starts = if (housingList != null) EconomicViewModel.getLatest(housingList, "Housing Starts") else null
        sb.append("Housing Starts: ").append(if (starts != null) String.format(Locale.US, "%.0f K units annualized (%s)", starts.value, starts.date) + freshnessTag(starts.date, 75) else "Unavailable").append("\n")
        val sales = if (housingList != null) EconomicViewModel.getLatest(housingList, "Existing Home Sales") else null
        sb.append("Existing Home Sales: ").append(if (sales != null) String.format(Locale.US, "%.0f K units (%s)", sales.value, sales.date) + freshnessTag(sales.date, 75) else "Unavailable").append("\n")

        val mbsList = vm.getMbsMortgageData().getValue()
        val mortgage = if (mbsList != null) EconomicViewModel.getLatest(mbsList, "30-Yr Mortgage Rate") else null
        sb.append("30-Yr Mortgage Rate: ").append(if (mortgage != null) String.format(Locale.US, "%.2f%% (%s)", mortgage.value, mortgage.date) + freshnessTag(mortgage.date, 21) else "Unavailable").append("\n")

        sb.append("\nSTOCK MARKET INDICES\n")
        var sp500List = vm.getSp500Data().getValue()
        if (sp500List == null || sp500List.isEmpty())
            sp500List = DashboardDataCache.load(requireContext(), EconomicViewModel.KEY_SP500)
        val sp500 = if (sp500List != null && !sp500List.isEmpty()) sp500List[sp500List.size - 1] else null
        sb.append("S&P 500: ").append(if (sp500 != null) String.format(Locale.US, "%.0f (%s)", sp500.value, sp500.date) + freshnessTag(sp500.date, 10) else "Unavailable").append("\n")

        var nasdaqList = vm.getNasdaqData().getValue()
        if (nasdaqList == null || nasdaqList.isEmpty())
            nasdaqList = DashboardDataCache.load(requireContext(), EconomicViewModel.KEY_NASDAQ)
        val nasdaq = if (nasdaqList != null && !nasdaqList.isEmpty()) nasdaqList[nasdaqList.size - 1] else null
        sb.append("Nasdaq: ").append(if (nasdaq != null) String.format(Locale.US, "%.0f (%s)", nasdaq.value, nasdaq.date) + freshnessTag(nasdaq.date, 10) else "Unavailable").append("\n")

        val vixList = vm.getVixData().getValue()
        val vix = if (vixList != null && !vixList.isEmpty()) vixList[vixList.size - 1] else null
        sb.append("VIX: ").append(if (vix != null) String.format(Locale.US, "%.2f (%s)", vix.value, vix.date) + freshnessTag(vix.date, 10) else "Unavailable").append("\n")

        sb.append("\nBOND MARKET SPREADS\n")
        val baaList = vm.getBaaSpreadData().getValue()
        val baaSp = if (baaList != null && !baaList.isEmpty()) baaList[baaList.size - 1] else null
        sb.append("BAA Corporate Spread: ").append(if (baaSp != null) String.format(Locale.US, "%.2f%% (%s)", baaSp.value, baaSp.date) + freshnessTag(baaSp.date, 10) else "Unavailable").append("\n")

        val hyList = vm.getHySpreadData().getValue()
        val hySpread = if (hyList != null && !hyList.isEmpty()) hyList[hyList.size - 1] else null
        sb.append("High Yield Spread: ").append(if (hySpread != null) String.format(Locale.US, "%.2f%% (%s)", hySpread.value, hySpread.date) + freshnessTag(hySpread.date, 10) else "Unavailable").append("\n")

        sb.append("\n")
        return sb.toString()
    }

    private fun buildRecentUpdatesQuery(): String {
        val vm = host().getViewModel()
        val sb = StringBuilder("Briefly summarise the most significant recent US economic developments based on the latest dashboard readings:")
        val pceList = vm.getPceData().getValue()
        if (pceList != null) {
            val pceRows = EconomicViewModel.filterBySeries(pceList, "PCE Price Index")
            if (pceRows.size >= 13) {
                val yoy = ((pceRows[pceRows.size - 1].value - pceRows[pceRows.size - 13].value) / pceRows[pceRows.size - 13].value) * 100.0
                sb.append(String.format(Locale.US, " PCE inflation %.2f%% YoY (Fed target 2%%);", yoy))
            }
        }
        val unemp = EconomicViewModel.getLatest(vm.getEmploymentData().getValue(), "Unemployment Rate")
        if (unemp != null) sb.append(String.format(Locale.US, " unemployment %.1f%% (%s);", unemp.value, unemp.date))
        val spread = vm.getCalculatedSpreadData().getValue()
        if (spread != null && !spread.isEmpty()) {
            val sv = spread[spread.size - 1].value
            sb.append(String.format(Locale.US, " 10Y-2Y spread %.2f%%%s.", sv, if (sv < 0) " (inverted)" else ""))
        }
        sb.append(" Which of these readings stand out most, and what do they collectively signal about the near-term economic outlook?")
        return sb.toString()
    }

    private fun buildRecentNewsQuery(): String {
        val cached: List<NewsItem> = NewsRepository.getInstance().cachedItems
        if (cached.isEmpty()) return "What are the most important recent developments in the US economy that I should be aware of right now?"
        val sb = StringBuilder("Based on the latest economic news headlines, what are the most significant stories right now and what do they signal for the economic outlook? Focus on the highest-impact items.")
        var count = 0
        var tier = 2
        while (tier >= 1 && count < 3) {
            for (item in cached) {
                if (count >= 3) break
                val title = item.title
                if (item.impactLevel == tier && title != null) {
                    sb.append(if (count == 0) " Recent headlines include: \"" else ", \"").append(title).append("\"")
                    count++
                }
            }
            tier--
        }
        if (count > 0) sb.append(".")
        return sb.toString()
    }

    /** Dark status/nav icons over the light ground while the assistant is up;
     *  restored on the way out. Night mode keeps light icons on dark ground.
     *  The theme's windowTranslucentStatus makes the system paint a grey scrim
     *  under the status bar whatever statusBarColor says — invisible over the
     *  navy header, a visible band over the assistant's ground. So the flag is
     *  cleared while the assistant is up (content still draws edge to edge)
     *  and put back on the way out. */
    private fun setLightSystemBars(assistantUp: Boolean) {
        val window = activity?.window ?: return
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val translucent = android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
        if (assistantUp) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(translucent)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
        } else {
            window.addFlags(translucent)
        }
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = assistantUp && !night
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
    }

    /** Leaves the full-screen assistant: pops its back-stack entry, and
     *  MainActivity restores the header, bottom nav and FAB. */
    fun close() {
        if (isAdded) parentFragmentManager.popBackStack(TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    override fun onDestroyView() {
        // Stop any in-flight request and any auto-retry queued to fire after
        // the sheet is gone — otherwise a scheduled retry can still run to
        // completion (or crash on requireActivity()) for a result nobody sees.
        cancelCurrentCall()
        setLightSystemBars(false)
        try {
            chatAdapter().unregisterAdapterDataObserver(conversationObserver)
            if (chatAdapter().turnActions === turnActions) chatAdapter().turnActions = null
        } catch (ignored: Exception) {}
        super.onDestroyView()
        binding = null
    }

    companion object {

        const val TAG = "AssistantFragment"

        /** AIC-02: within this distance of the bottom counts as "at the bottom". */
        private const val FOLLOW_SLOP_DP = 100

        /** AIC-03: appended to a stopped answer; stored with it, so it survives a restart. */
        private const val ARG_PREFILL = "prefill_query"
        private const val ARG_SCREEN_CONTEXT = "screen_context"
        private const val ARG_IMAGE_URI = "image_uri"
        private const val ARG_CONCISE = "concise_gesture"

        /** TICKET-P2-4: marks dynamically added follow-up chips so a new
         *  answer can clear its predecessors without touching static chips. */

        /** MOT-03: four max — five wraps and breaks the centre-out reading. */
        private const val MAX_PROMPT_CHIPS = 4

        /** Pantrii port U4: the start screen shows four cards, like Pantrii. */
        const val MAX_PROMPT_CARDS = 4

        /** The prompts the start screen renders: the first [MAX_PROMPT_CARDS]. */
        @JvmStatic
        fun startCardPrompts(prompts: List<String>): List<String> = prompts.take(MAX_PROMPT_CARDS)

        /** Cap request context at the last N history entries (N/2 turns). */
        private const val HISTORY_CAP = 20

        /** AIC-09: stream repaint window. */
        private const val STREAM_FLUSH_MS = 50L

        /** Max tool-call round-trips per user question. */
        private const val MAX_TOOL_DEPTH = 3

        /** TICKET-P2-2: max continuation rounds whose only tool calls were
         *  render_chart. Charts are exempt from MAX_TOOL_DEPTH (a rendering
         *  directive, not a data lookup), so they need their own bound — without
         *  one, depth-free rounds could loop indefinitely. Each such round can
         *  carry several render_chart calls, so this caps rounds, not charts. */
        private const val MAX_CHART_ROUNDS = 3

        /** Total automatic retries allowed for one user question, across every
         *  sendTurn() invocation and tool-loop round — not per-invocation. Without
         *  this a persistently "transient-looking" failure (e.g. repeated
         *  overloaded_error) can retry indefinitely. */
        private const val MAX_RETRIES_PER_TURN = 3

        /** AI Law 6: model ids, shared with DailyBriefWorker via ModelConfig so a
         *  model rotation only needs one edit (TICKET-F / review F9). */
        /** AIC-05: what Continue sends after a stopped answer. */
        private const val CONTINUE_PROMPT = "Continue your previous answer from where it stopped."

        /** KTD1: pushes [fragment] full-screen onto the activity's
         *  fragmentContainer with a back-stack entry. MainActivity's back-stack
         *  listener hides and restores the header, bottom nav and FAB.
         *
         *  Add + hide, not replace: replace destroys the tab's view, and
         *  Overview came back stuck on its loading skeletons after a pop. */
        @JvmStatic
        fun show(fm: FragmentManager, fragment: AssistantFragment) {
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            val tx = fm.beginTransaction().setReorderingAllowed(true)
            fm.findFragmentById(R.id.fragmentContainer)?.let { tx.hide(it) }
            tx.add(R.id.fragmentContainer, fragment, TAG)
                .addToBackStack(TAG)
                .commit()
        }

        /** Opens the assistant with a question that is sent immediately. */
        @JvmStatic
        fun newInstance(prefillQuery: String?): AssistantFragment {
            val sheet = AssistantFragment()
            val args = Bundle()
            args.putString(ARG_PREFILL, prefillQuery)
            sheet.arguments = args
            return sheet
        }

        /**
         * Opens the sheet from a one-tap gesture (card/chart long-press): screen
         * context is injected and the next reply uses the concise length tier
         * (AI Laws 7, 8).
         */
        @JvmStatic
        fun newInstanceForGesture(
            prefillQuery: String?, screenContext: String?, concise: Boolean
        ): AssistantFragment {
            val sheet = newInstance(prefillQuery)
            val args = sheet.arguments
            if (args != null) {
                if (screenContext != null && !screenContext.isEmpty())
                    args.putString(ARG_SCREEN_CONTEXT, screenContext)
                args.putBoolean(ARG_CONCISE, concise)
            }
            return sheet
        }

        /** Opens the sheet with a shared image queued for analysis.
         *  prefillQuery is nullable — MainActivity passes null for a bare share. */
        @JvmStatic
        fun newInstanceForImage(imageUri: Uri, prefillQuery: String?): AssistantFragment {
            val sheet = AssistantFragment()
            val args = Bundle()
            args.putString(ARG_PREFILL, prefillQuery)
            args.putString(ARG_IMAGE_URI, imageUri.toString())
            sheet.arguments = args
            return sheet
        }

        /** Extracts the server's error message from a non-2xx response body, if present. */
        private fun readApiError(r: Response): String {
            val fallback = "API error " + r.code + "."
            return try {
                val body = r.body?.string() ?: ""
                val err = JSONObject(body.trim()).optJSONObject("error")
                val msg = if (err != null) err.optString("message", "") else ""
                if (msg.isEmpty()) fallback else "$fallback $msg"
            } catch (ignored: Exception) { fallback }
        }

        /** Reads the next SSE line; a user-cancelled stream ends cleanly instead of throwing. */
        private fun readLineSafe(source: okio.BufferedSource, call: Call): String? {
            try {
                return source.readUtf8Line()
            } catch (e: IOException) {
                if (call.isCanceled()) return null
                throw e
            }
        }

        /** Joins all text deltas from a complete SSE payload delivered as one string. */
        private fun extractTextFromSseString(body: String): String {
            val acc = StringBuilder()
            for (line in body.split("\n")) {
                if (!line.startsWith("data:")) continue
                val payload = line.substring(5).trim()
                if (payload.isEmpty()) continue
                try {
                    val evt = JSONObject(payload)
                    if ("content_block_delta" == evt.optString("type")) {
                        val delta = evt.optJSONObject("delta")
                        if (delta != null) acc.append(delta.optString("text", ""))
                    }
                } catch (ignored: Exception) {}
            }
            return acc.toString()
        }

        /**
         * AI Law 15: tag snapshot values whose date is older than expected for
         * their release cadence, so the model presents them as possibly outdated
         * instead of as current fact (a prior session saw an Aug-2016 Nasdaq date
         * flow straight into a confident answer).
         */
        private fun freshnessTag(dateStr: String?, maxAgeDays: Int): String {
            return freshnessTag(dateStr, maxAgeDays, System.currentTimeMillis())
        }
    }
}
