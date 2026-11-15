package io.chagok.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import io.chagok.app.BuildConfig
import io.chagok.app.R
import io.chagok.app.data.ApiError
import io.chagok.app.data.ChagokApi
import io.chagok.app.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phone app: P01–P13, laid out after the wireframe canvas (artifact N4BmsuVCA7ZDY79QVjjMvP).
 * Sends a TripPlan to the Pleos app (pleos repo) and builds the record (P12) from the DriveEvents it sends back.
 * Until the transport exists, events arrive as an intent extra (see CONTRACT.md).
 */
class PhoneActivity : ScreenActivity() {
    override val layout = R.layout.activity_phone
    override val startScreen = Screen.HOME
    private val selectedPlaces = linkedSetOf("anmok", "chodang")
    private val selectedPhotos = linkedSetOf<Int>()
    private val recordedPhotos = linkedSetOf<Int>()
    private var filter = "전체"
    private var photoTab = "제안"
    /** DriveEvents of the latest trip, in arrival order. A different tripId starts a new record. */
    private val driveEvents = mutableListOf<DriveEvent>()
    /** Places the user recorded on the phone (P12), as "name\u0001detail". */
    private val moments = mutableListOf<String>()
    /** Current-location suggestion on P12: name to detail, or null before/after asking. */
    private var nearby: Pair<String, String>? = null
    private var locating = false
    private var recapIndex = 0
    private var pastIndex = 0
    /**
     * Backend session, kept in this app's private preferences: account token (24 h), email, and the server
     * tripId of the trip on screen. ponytail: move the token to Android Keystore before release (Pleos privacy guide).
     */
    private val session by lazy { getSharedPreferences("chagok-session", MODE_PRIVATE) }
    private val api by lazy { ChagokApi(BuildConfig.API_BASE_URL) { session.getString("token", null) } }
    private val signedInAs: String? get() = session.getString("email", null)?.takeIf { session.getString("token", null) != null }
    private val serverTripId: String? get() = session.getString("tripId", null)
    private val poller = Handler(Looper.getMainLooper())
    private val pollEvents = object : Runnable {
        override fun run() { refreshEvents(); poller.postDelayed(this, 10_000) }
    }
    private var shownScreen: Screen? = null
    private lateinit var actions: LinearLayout

    // Wireframe palette (res/values/colors.xml phone_*).
    private val pInk by lazy { getColor(R.color.phone_ink) }
    private val pMuted by lazy { getColor(R.color.phone_muted) }
    private val pLine by lazy { getColor(R.color.phone_line) }
    private val pFill by lazy { getColor(R.color.phone_fill) }
    private val pAccent by lazy { getColor(R.color.phone_accent) }
    private val pAccentSoft by lazy { getColor(R.color.phone_accent_soft) }
    private val pSurface by lazy { getColor(R.color.phone_surface) }
    private val pDot by lazy { getColor(R.color.phone_dot) }
    private val pRail by lazy { getColor(R.color.phone_rail) }
    private val shadow = Color.rgb(60, 70, 50)
    // P16 page swipe: where the finger went down, whether this touch became a sideways drag, its speed.
    private var swipeX = 0f
    private var swipeY = 0f
    private var swiping = false
    private var velocity: VelocityTracker? = null
    private var pastPager: FrameLayout? = null // P16: the moving part under the fixed header; current trip + neighbours while turning
    private var pastTurn: ValueAnimator? = null
    private var summaryPast: Int? = null // 여행 요약 of a past trip (P16) instead of the current one

    /** Screens that show the floating + button; the 5-step setup, arrival, photo picker and recap are full-screen flows. */
    private val plusScreens = setOf(Screen.HOME, Screen.START, Screen.RECOMMENDATIONS, Screen.ITINERARY, Screen.TIMELINE, Screen.SUMMARY, Screen.MY)
    /** ponytail: sample stand-in for "far from the route"; the recommendation API will return this. */
    private val farFromRoute = setOf("jeongdongjin")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // bg_chagok of the car app: lavender at the top fading to warm paper.
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).background = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            colors = intArrayOf(getColor(R.color.phone_bg_top), getColor(R.color.phone_bg_mid), getColor(R.color.phone_bg_bottom))
        }
        if (state == null) { restoreSavedTrip(); render(); receive(intent) }
    }

    /**
     * P16 page swipe. The header (이전 · 지난 여행 돌아보기 · n / N · dots) stays; only the trip below moves. When the touch turns
     * sideways the previous and next trips are built next to it, so they slide in attached — no empty gap. Let go past a quarter
     * of the width or with a flick and it turns (settlePast), otherwise it springs back; rubber band at the first/last trip.
     * Vertical scrolling and taps work as usual until the touch is clearly sideways; then the children get a cancel.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        val pager = pastPager
        if (screen != Screen.PAST || pager == null) return super.dispatchTouchEvent(e)
        if (pastTurn?.isRunning == true) return true // let the page finish turning
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            swipeX = e.rawX; swipeY = e.rawY; swiping = false
            velocity?.recycle(); velocity = VelocityTracker.obtain()
        }
        velocity?.addMovement(e)
        val dx = e.rawX - swipeX; val dy = e.rawY - swipeY
        if (!swiping && e.actionMasked == MotionEvent.ACTION_MOVE && kotlin.math.abs(dx) > ViewConfiguration.get(this).scaledTouchSlop * 2 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
            swiping = true
            MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(this); recycle() }
            pastNeighbours(pager)
        }
        if (!swiping) return super.dispatchTouchEvent(e)
        val edge = (dx > 0 && pastIndex == 0) || (dx < 0 && pastIndex == pastTrips.lastIndex)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> slidePast(pager, if (edge) dx / 3 else dx)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val flick = kotlin.math.abs(vx) > dp(700) && (vx < 0) == (dx < 0)
                val turn = !edge && e.actionMasked == MotionEvent.ACTION_UP && (kotlin.math.abs(dx) > pager.width / 4f || flick)
                settlePast(pager, if (turn) (if (dx < 0) 1 else -1) else 0)
                velocity?.recycle(); velocity = null; swiping = false
            }
        }
        return true
    }

    /** Distance between two trip pages: one page plus a 24dp gap. */
    private fun pastSpan(pager: View) = pager.width + dp(24).toFloat()

    /** Builds the previous/next trip beside the current one (tag = −1 / +1), lined up with what is on screen now. */
    private fun pastNeighbours(pager: FrameLayout) {
        if (pager.childCount > 1) return
        val y = (findViewById<ScrollView>(R.id.screen_scroll).scrollY - pager.top).coerceAtLeast(0).toFloat()
        listOf(-1, 1).forEach { step ->
            val i = pastIndex + step
            if (i in pastTrips.indices) pager.addView(pastPage(i).apply { tag = step; translationY = y; translationX = step * pastSpan(pager) }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun slidePast(pager: FrameLayout, dx: Float) {
        for (i in 0 until pager.childCount) pager.getChildAt(i).let { it.translationX = dx + ((it.tag as? Int) ?: 0) * pastSpan(pager) }
    }

    /**
     * Finish a turn: [step] −1 / +1 slides the neighbour into place, 0 springs back. After a turn the screen is rebuilt for the
     * new trip (in the animator callback, not inside touch dispatch) and scrolled so the page stays exactly where it was seen.
     * With animations off in the system settings the animator ends at once.
     */
    private fun settlePast(pager: FrameLayout, step: Int) {
        if (step != 0 && pastIndex + step !in pastTrips.indices) return
        pastNeighbours(pager)
        val current = (0 until pager.childCount).map(pager::getChildAt).first { it.tag == null }
        pastTurn = ValueAnimator.ofFloat(current.translationX, -step * pastSpan(pager)).apply {
            duration = if (step == 0) 220 else 260
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { slidePast(pager, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (isDestroyed || pastPager !== pager) return
                    if (step == 0) { (0 until pager.childCount).map(pager::getChildAt).filter { it.tag != null }.forEach(pager::removeView); return }
                    val scroll = findViewById<ScrollView>(R.id.screen_scroll)
                    val keep = minOf(scroll.scrollY, pager.top)
                    pastIndex += step; render(); scroll.scrollTo(0, keep)
                }
            })
            start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receive(intent)
    }

    /** Pleos → Phone entry point. ponytail: an intent extra stands in for the transport until the backend relay exists. */
    private fun receive(intent: Intent?) {
        val json = intent?.getStringExtra(TripContract.EXTRA_EVENT) ?: return
        intent.removeExtra(TripContract.EXTRA_EVENT)
        val event = runCatching { DriveEvent.fromJson(json) }.getOrElse {
            Log.w("ChagokContract", "Ignoring invalid DriveEvent", it); message("차에서 온 기록을 읽지 못했어요."); return
        }
        Log.i("ChagokContract", "Phone received $json")
        if (driveEvents.firstOrNull()?.tripId != event.tripId) driveEvents.clear()
        driveEvents.add(event)
        if (event.type == DriveEvent.Type.TRIP_FINISHED) navigate(Screen.TIMELINE) else render()
    }

    override fun render() {
        content = findViewById(R.id.screen_content); content.removeAllViews()
        content.clipChildren = true; pastPager = null // P16 turns its pages across the side padding (see past())
        actions = findViewById(R.id.bottom_action); actions.removeAllViews(); actions.visibility = View.GONE
        findViewById<View>(R.id.sheet_overlay).visibility = View.GONE
        title = "${screen.board} ${screen.title}"
        if (screen == Screen.START) {
            // The fan opens over whatever screen + was pressed on.
            val opened = screen; screen = history.lastOrNull()?.takeIf { it != Screen.START } ?: Screen.HOME
            draw(); screen = opened; fanOut()
        } else draw()
        plusBar()
        poller.removeCallbacks(pollEvents)
        if (screen == Screen.TIMELINE && signedInAs != null && serverTripId != null && !isFinishing) poller.postDelayed(pollEvents, 10_000)
        if (shownScreen != screen && screen == Screen.TIMELINE) refreshEvents()
        if (shownScreen != screen) { shownScreen = screen; findViewById<ScrollView>(R.id.screen_scroll).let { it.post { it.scrollTo(0, 0) } } }
    }

    private fun draw() {
        when (screen) {
            Screen.HOME -> home()
            Screen.DESTINATION -> destination()
            Screen.COMPANIONS -> companions()
            Screen.INTERESTS -> interests()
            Screen.MOOD -> mood()
            Screen.IMPORT -> importVideos()
            Screen.RECOMMENDATIONS -> recommendations()
            Screen.ITINERARY -> itinerary()
            Screen.ARRIVAL -> arrival()
            Screen.PHOTOS -> photos()
            Screen.TIMELINE -> timeline()
            Screen.SUMMARY -> summary()
            Screen.MY -> my()
            Screen.RECAP -> recap()
            Screen.PAST -> past()
            else -> Unit
        }
    }

    // ── P01 · P02 ───────────────────────────────────────────────────────────

    private fun home() {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("여행", 28, pInk, bold = true), lp(0, WRAP, 1f))
        head.addView(iconButton(R.drawable.ic_person, "MY · 내 정보") { navigate(Screen.MY) }, lp(dp(40), dp(40)))
        put(head, gap = 16)
        val places = itineraryPlaces()
        // v4 home: the trip as a photo card (date · who · mood on glass tags, the voice mark and the greeting on it).
        photoCard(photoFor(draft.destination), 380, tags = listOf(dateLabel(draft.departure), whoShort(), draft.mood), withMark = true, hello = helloLine().toString(),
            description = "${tripName()}, ${dateLabel(draft.departure)}, 장소 ${places.size}곳. 일정 보기") { navigate(Screen.ITINERARY) }
        if (places.isNotEmpty()) grid(places.take(6).map { p ->
            photoCard(photoFor(p.name), 150, radius = 16f, tags = listOf(p.time), title = p.name, titleSize = 15, into = null, description = "${p.time} ${p.name}")
        }, 3, 150)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("일정 바꾸기", primary = false) { navigate(Screen.ITINERARY) }, lp(0, dp(52), 1f))
        buttons.addView(actionButton("차로 보내기", primary = true, icon = R.drawable.ic_send) {
            if (places.isEmpty()) message("일정에 장소를 먼저 담아 주세요.") else sendToCar(places)
        }, lp(0, dp(52), 1.6f).apply { marginStart = dp(8) })
        put(buttons, gap = 24)
        tile("유튜브에서 본 곳으로 다음 여행 찾기", "좋아요, 재생목록, 구독 채널", iconRes = R.drawable.ic_fan_reco) { navigate(Screen.RECOMMENDATIONS) }
        tile("이번 여행 기록", "${draft.destination} · 남긴 장소 ${2 + moments.size}곳", thumb = photoFor("안목 커피거리"), chevron = true) { navigate(Screen.TIMELINE) }
        tile("다른 여행 고르기", "저장한 여행 · 새 여행 만들기", iconRes = R.drawable.ic_swap, chevron = true) { pickTrip() }
        tile("지난 여행 돌아보기", "여행 ${pastTrips.size}개", thumb = R.drawable.photo_sokcho, chevron = true) { showPast(0, push = true) }
    }
    /** Car start line ("오늘은 속초로\n떠나는 날이에요"), counted down on the phone before the day. */
    private fun helloLine(): CharSequence {
        val dest = draft.destination
        val days = date(draft.departure)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
        return when {
            days == null -> emphasize(listOf(dest to true, "${toward(dest)} 떠날 날을\n정해 볼까요?" to false))
            days > 0 -> emphasize(listOf(dest to true, "${toward(dest)} 떠나기까지\n" to false, "${days}일 남았어요" to false))
            days == 0L -> emphasize(listOf("오늘은 " to false, dest to true, "${toward(dest)}\n떠나는 날이에요" to false))
            else -> emphasize(listOf(dest to true, " 여행에서\n돌아왔어요" to false))
        }
    }

    /** Car start meta: "부모님과 함께" / "혼자 떠나요". */
    private fun withWho(): String {
        val who = draft.companions.toList()
        return if (who.isEmpty() || who == listOf("혼자")) "혼자 떠나요" else "${who.joinToString(", ").let { it + withParticle(it) }} 함께"
    }

    /** 으로/로: 로 after a vowel or ㄹ. */
    private fun toward(word: String): String {
        val c = word.lastOrNull() ?: return "로"
        if (c !in '가'..'힣') return "로"
        val tail = (c - '가') % 28
        return if (tail == 0 || tail == 8) "로" else "으로"
    }

    private class FanItem(val label: String, val description: String, val icon: Int, val go: () -> Unit)

    /** The + menu (P02): five actions fan out in an arc above the button, which turns into a close (×). */
    private fun fanOut() {
        val layer = findViewById<FrameLayout>(R.id.fan_layer); layer.removeAllViews()
        val scrim = findViewById<View>(R.id.sheet_scrim)
        findViewById<View>(R.id.sheet_overlay).visibility = View.VISIBLE
        scrim.setBackgroundColor(Color.argb(204, 7, 9, 12)) // v4 .fan .veil; scrim.setOnClickListener { closeFan() }
        val animate = shownScreen != Screen.START
        val items = listOf(
            FanItem("새 여행", "새 여행 만들기, 목적지부터 5단계", R.drawable.ic_fan_trip) { newTrip(); leaveFanTo(Screen.DESTINATION) },
            FanItem("추천 장소", "추천 장소 보기, 유튜브에서 찾은 곳", R.drawable.ic_fan_reco) { leaveFanTo(Screen.RECOMMENDATIONS) },
            FanItem("지금 기록", "지금 이 장소 기록, 현재 위치로 근처 장소 찾기", R.drawable.ic_fan_pin) { leaveFanTo(Screen.TIMELINE); locateHere() },
            FanItem("기록 보기", "기록 보기", R.drawable.ic_fan_list) { leaveFanTo(Screen.TIMELINE) },
            FanItem("모아보기", "한눈에 모아보기, 기록을 한 장씩 넘겨 보기", R.drawable.ic_fan_recap) { recapIndex = 0; leaveFanTo(Screen.RECAP) },
        )
        val radius = dpf(132f)
        // Item views sit with their circle centred on the + button (bar 84dp, button 60dp → centre 42dp up).
        items.forEachIndexed { i, item ->
            val angle = Math.toRadians(165.0 - i * 150.0 / (items.size - 1))
            val tx = (cos(angle) * radius).toFloat(); val ty = (-sin(angle) * radius).toFloat()
            val view = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; tag = "fan"
                // Label and circle are one target, so tapping the word works too.
                isClickable = true; isFocusable = true; contentDescription = item.description; setOnClickListener { item.go() }
                addView(label(item.label, 12, Color.WHITE, bold = true).apply {
                    gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lp(dp(88), WRAP).apply { bottomMargin = dp(6) })
                addView(ImageButton(this@PhoneActivity, null, 0).apply {
                    setImageResource(item.icon); imageTintList = ColorStateList.valueOf(pInk); scaleType = ImageView.ScaleType.CENTER
                    background = oval(pSurface); foreground = ripple(28f); elevation = dpf(3f)
                    isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, lp(dp(56), dp(56)))
            }
            layer.addView(view, FrameLayout.LayoutParams(dp(88), WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(42 - 28) })
            if (animate) {
                view.alpha = 0f; view.scaleX = 0.4f; view.scaleY = 0.4f
                view.animate().translationX(tx).translationY(ty).alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 30L).setDuration(280).setInterpolator(OvershootInterpolator(1.3f)).start()
            } else { view.translationX = tx; view.translationY = ty }
        }
        val close = Button(this, null, 0).apply {
            text = "+"; textSize = 30f; setTextColor(pInk); gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(3))
            background = oval(pSurface); foreground = ripple(30f); contentDescription = "메뉴 닫기"
            setOnClickListener { closeFan() }
        }
        layer.addView(close, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        if (animate) { scrim.alpha = 0f; scrim.animate().alpha(1f).setDuration(180).start(); close.animate().rotation(45f).setDuration(220).start() }
        else { scrim.alpha = 1f; close.rotation = 45f }
    }

    private var fanClosing = false

    /** Folds the items back into the button, then returns to the screen underneath. */
    private fun closeFan() {
        if (fanClosing) return
        fanClosing = true
        val layer = findViewById<FrameLayout>(R.id.fan_layer)
        for (i in 0 until layer.childCount) {
            val child = layer.getChildAt(i)
            if (child.tag == "fan") child.animate().translationX(0f).translationY(0f).alpha(0f).scaleX(0.4f).scaleY(0.4f).setStartDelay(0).setDuration(160).start()
            else child.animate().rotation(0f).setDuration(160).start()
        }
        findViewById<View>(R.id.sheet_scrim).animate().alpha(0f).setDuration(170).withEndAction { fanClosing = false; onBackPressed() }.start()
    }

    /** Leaves the fan for [target] without keeping the fan itself in the back stack. */
    private fun leaveFanTo(target: Screen) {
        if (screen == Screen.START && history.isNotEmpty()) screen = history.removeAt(history.lastIndex)
        if (target == screen) render() else navigate(target)
    }

    // ── P03–P07 · 5-step setup ──────────────────────────────────────────────

    private fun destination() {
        wizardHeader(1, "목적지", "어디로 갈까요?")
        photoCard(photoFor(draft.destination), 180, title = draft.destination)
        val input = EditText(this).apply {
            setSingleLine(true); setText(draft.destination); hint = "도시 또는 장소"; textSize = 17f; typeface = font(700); setTextColor(pInk); setHintTextColor(pMuted)
            background = shape(pSurface, null, 999f); setPadding(dp(18), 0, dp(18), 0); minHeight = dp(56); contentDescription = "목적지"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_fan_pin, 0, 0, 0); compoundDrawablePadding = dp(12)
            compoundDrawableTintList = ColorStateList.valueOf(pMuted)
            addTextChangedListener(SimpleTextWatcher { draft.destination = it })
        }
        put(input, gap = 12)
        grid(listOf(fieldButton("출발", dateLabel(draft.departure)) { chooseDate(false) },
            fieldButton("종료", dateLabel(draft.endDate), muted = draft.endDate == "미정") { chooseDate(true) }), 2, 56, gap = 12)
        val toggle = Switch(this).apply {
            isChecked = draft.recordRoute; contentDescription = "이동 기록 켜기"; setOnCheckedChangeListener { _, on -> draft.recordRoute = on }
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(pInk, pDot))
        }
        tile("이동 기록 켜기", "여행 중 경로를 자동으로 남겨요", trailing = toggle, minHeight = 64, gap = 6) { toggle.toggle() }
        put(label("지금은 설정만 저장해요. 위치는 아직 수집하지 않아요.", 12, pMuted), gap = 16)
        put(segmented(listOf("나만", "가족·친구", "전체"), { it == draft.visibility }) { draft.visibility = it; render() })
        primary("다음") {
            if (draft.destination.isBlank()) input.error = "목적지를 입력해 주세요"
            else { draft.destination = draft.destination.trim(); navigate(Screen.COMPANIONS) }
        }
    }
    private fun companions() {
        wizardHeader(2, "동승자", "누구와 함께 가나요?", "여러 명을 고를 수 있어요")
        listOf("부모님" to "편한 이동, 조용한 이야기", "아이와 가족" to "쉬는 곳, 아이가 즐길 곳", "연인" to "분위기 있는 장소", "친구" to "활동적인 장소", "혼자" to "내 속도로")
            .forEach { (name, hint) ->
                tile(name, hint, selected = name in draft.companions, minHeight = 64) {
                    if (name in draft.companions) draft.companions.remove(name)
                    else { if (name == "혼자") draft.companions.clear() else draft.companions.remove("혼자"); draft.companions.add(name) }
                    render()
                }
            }
        primary("다음") { if (draft.companions.isEmpty()) message("동승자를 하나 이상 골라 주세요.") else navigate(Screen.INTERESTS) }
    }
    private fun interests() {
        wizardHeader(3, "관심사", "무엇에 끌리나요?", "3개까지 골라요")
        put(chipFlow(listOf("지역 이야기", "맛집", "추억", "역사", "자연·바다", "카페", "사진 명소", "전시·문화", "시장", "산책"), { it in draft.interests }) {
            if (it in draft.interests) draft.interests.remove(it) else if (draft.interests.size < 3) draft.interests.add(it) else message("관심사는 3개까지 고를 수 있어요.")
            render()
        }, gap = 16)
        put(label("지난 여행의 선택을 기억해 먼저 보여줘요.", 13, pMuted))
        primary("다음") { if (draft.interests.isEmpty()) message("관심사를 하나 이상 골라 주세요.") else navigate(Screen.MOOD) }
    }
    private fun mood() {
        wizardHeader(4, "분위기", "어떤 분위기가 좋아요?")
        listOf(Triple("잔잔한", "여유 있게, 이야기는 길게", R.drawable.photo_lakesun1), Triple("활기찬", "가볍고 빠르게", R.drawable.photo_surf),
            Triple("감성적인", "추억과 음악 위주", R.drawable.photo_lakesun2)).forEach { (name, hint, photo) ->
            photoCard(photo, 112, title = name, titleSize = 17, sub = hint, selected = name == draft.mood, gap = 8) { draft.mood = name; render() }
        }
        put(segmented(listOf("느긋", "보통", "알차게"), { it == draft.density }) { draft.density = it; render() }, gap = 8)
        put(label("밀도에 따라 하루 장소 수와 머무는 시간이 달라져요.", 12, pMuted))
        primary("다음") { navigate(Screen.IMPORT) }
    }
    private fun importVideos() {
        wizardHeader(5, "유튜브", "유튜브에서 찾아볼까요?", "본 영상 속 장소를 일정 후보로 모아요. 건너뛰어도 돼요")
        grid(listOf(R.drawable.photo_cafe2, R.drawable.photo_tofu, R.drawable.photo_waves).map { roundImage(it, 16f) }, 3, 150)
        // YouTube Data API (OAuth) can read liked videos, own playlists and subscriptions; history only via Takeout.
        tile("유튜브 연결", "좋아요, 재생목록, 구독", iconRes = R.drawable.ic_fan_recap, trailing = pill("연결", pAccent)) { soon(repository.importFrom("유튜브")) }
        tile("링크 공유로 보내기", "다른 앱에서 차곡으로 공유", iconRes = R.drawable.ic_link, trailing = pill("예정", pMuted)) { soon(repository.importFrom("공유 링크")) }
        tile("시청 기록 파일", "Google 테이크아웃 파일", iconRes = R.drawable.ic_file, trailing = pill("파일", pMuted), gap = 12) { soon(repository.importFrom("시청 기록 파일")) }
        put(label("시청 기록과 '나중에 볼 동영상'은 유튜브가 앱에 열어 주지 않아 파일로만 가져올 수 있어요.", 12, pMuted))
        primary("장소 찾기") { navigate(Screen.RECOMMENDATIONS) }
        textButton("건너뛰기", center = true, into = actions) { navigate(Screen.ITINERARY) }
    }
    // ── P08 · P09 ───────────────────────────────────────────────────────────

    private fun recommendations() {
        pageHeader("추천", sub = "유튜브에서 찾은 장소예요. 샘플이에요.")
        put(segmented(listOf("전체", "좋아요", "재생목록", "구독"), { it == filter }) { filter = it; render() })
        repository.places.filter { filter == "전체" || it.group == filter }.forEach { place ->
            val on = place.id in selectedPlaces
            val minutes = Regex("(\\d+)\\s*분").find(place.distance)?.groupValues?.get(1)
            val km = place.distance.substringBefore("·").trim()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(12), dp(16), dp(12))
                background = if (on) shape(pSurface, pInk, 24f, strokeDp = 2f) else shape(pSurface, null, 24f)
                isClickable = true; isFocusable = true; foreground = ripple(24f); isSelected = on
                contentDescription = listOf(place.name, minutes?.let { "${it}분 거리" }, km, place.source, if (on) "선택됨" else "선택 안 됨").filterNotNull().joinToString(", ")
                setOnClickListener { if (!selectedPlaces.add(place.id)) selectedPlaces.remove(place.id); render() }
                alpha = if (place.id in farFromRoute) 0.55f else 1f
            }
            row.addView(roundImage(photoFor(place.name), 16f), lp(dp(96), dp(96)).apply { marginEnd = dp(14) })
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(place.name, 17, pInk, bold = true))
                addView(TextView(this@PhoneActivity).apply { text = fig(listOfNotNull(minutes?.let { it to true }, minutes?.let { "분 거리   " to false }, km to false)); setPadding(0, dp(2), 0, dp(4)) })
                addView(label(listOf(place.source, place.reason, place.open).filter(String::isNotEmpty).joinToString("\n"), 13, pMuted))
            }, lp(0, WRAP, 1f))
            row.addView(checkMark(on), lp(dp(28), dp(28)).apply { marginStart = dp(8) })
            put(row, gap = 8)
        }
        primary("${selectedPlaces.size}곳을 일정에 담기") {
            if (selectedPlaces.isEmpty()) message("장소를 하나 이상 골라 주세요.")
            else { itineraryIds.clear(); itineraryIds.addAll(selectedPlaces); navigate(Screen.ITINERARY) }
        }
    }
    private fun itinerary() {
        pageHeader("${draft.destination} 일정", trailing = dateLabel(draft.departure))
        val places = itineraryPlaces()
        if (places.isNotEmpty()) {
            val total = places.sumOf { Regex("(\\d+)").find(it.duration)?.value?.toInt() ?: 0 }
            val parts = mutableListOf("총 " to false)
            if (total >= 60) { parts += "${total / 60}" to true; parts += "시간 " to false }
            if (total % 60 > 0 || total < 60) { parts += "${total % 60}" to true; parts += "분 머물러요     " to false } else parts += "머물러요     " to false
            parts += "${places.size}" to true; parts += "곳 들러요" to false
            put(TextView(this).apply { text = fig(parts, big = 30) }, gap = 16)
        }
        map()
        if (places.isEmpty()) put(label("담은 장소가 없어요. 추천에서 골라 주세요.", 13, pMuted))
        else timelineCard(places.mapIndexed { index, place ->
            TimeRow(place.time, place.name, sub = place.open.ifEmpty { null }, right = place.duration, photo = photoFor(place.name),
                description = listOf("${index + 1}번째 ${place.name}", place.time, place.duration, place.open).filter(String::isNotEmpty).joinToString(", "))
        }, meta = "≡ 를 끌어서 순서 바꾸기", tag = draft.mood, onMove = { from, to -> val id = itineraryIds.removeAt(from); itineraryIds.add(to, id) })
        tile("사이에 들를 곳 더 찾기", "동선 근처 장소를 제안해요", iconRes = R.drawable.ic_fan_reco, dashed = true) { navigate(Screen.RECOMMENDATIONS) }
        actions.visibility = View.VISIBLE
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(actionButton("저장하기", primary = false) { if (places.isEmpty()) message("일정에 장소를 먼저 담아 주세요.") else saveTrip(itineraryPlaces()) },
            lp(0, dp(52), 1f).apply { marginEnd = dp(8) })
        buttons.addView(actionButton("차로 보내기", primary = true, icon = R.drawable.ic_send) { if (places.isEmpty()) message("일정에 장소를 먼저 담아 주세요.") else sendToCar(itineraryPlaces()) },
            lp(0, dp(52), 1.6f))
        actions.addView(buttons, lp(MATCH, WRAP))
    }
    // ── P10–P13 · record ────────────────────────────────────────────────────

    private fun arrival() {
        putBack(gap = 16)
        put(label("12:34", 76, pInk).apply { gravity = Gravity.CENTER; typeface = num(300); fontFeatureSettings = "tnum"; typeface = num(650) }, gap = 4)
        put(label(fullDateLabel(draft.departure), 13, pMuted).apply { gravity = Gravity.CENTER }, gap = 28)
        val note = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); background = cardBg() }
        note.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(mark(32), lp(dp(32), dp(32)).apply { marginEnd = dp(8) }); addView(label("차곡, 지금", 13, pMuted))
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        note.addView(photoCard(R.drawable.photo_tofu, 160, radius = 16f, title = "초당 순두부 거리에 도착했어요", titleSize = 17, into = null), lp(MATCH, dp(160)).apply { bottomMargin = dp(16) })
        note.addView(label("이 장소의 사진 한 장을 남길까요?", 15, pMuted, medium = true), lp(MATCH, WRAP).apply { bottomMargin = dp(16) })
        note.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(actionButton("나중에", primary = false) { navigate(Screen.TIMELINE) }, lp(0, dp(52), 1f).apply { marginEnd = dp(8) })
            addView(actionButton("사진 남기기", primary = true) { navigate(Screen.PHOTOS) }, lp(0, dp(52), 1f))
        })
        put(note, gap = 12)
        tile("안목 커피거리 기록 완료", "1시간 전, 사진 3장", thumb = photoFor("안목 커피거리"), gap = 12).alpha = 0.55f
        put(label("도착 알림 예시예요. 위치 감지와 시스템 알림은 연결 전이에요.", 13, pMuted).apply { gravity = Gravity.CENTER })
    }
    private fun photos() {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(backButton(), lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
        top.addView(label("초당 순두부 거리", 17, pInk, bold = true), lp(0, WRAP, 1f))
        put(top, gap = 16)
        put(segmented(listOf("제안", "내 앨범", "촬영"), { it == photoTab }, display = { if (it == "제안") "제안 6" else it }) { photoTab = it; render() })
        if (photoTab != "제안") { put(label("$photoTab 연동 전이에요. 기기의 사진·카메라에 접근하지 않아요.", 13, pMuted)); return }
        put(label("이 장소 200m 안, 12:20~13:40에 찍은 사진", 13, pMuted), gap = 12)
        grid((1..6).map { photoTile(it) }, 3, 116)
        put(label("고른 사진만 올라가요. 지금은 예시 사진이에요. 앨범에 접근하지 않아요.", 13, pMuted))
        primary("${selectedPhotos.size}장 담기") {
            if (selectedPhotos.isEmpty()) message("사진을 하나 이상 골라 주세요.") else { recordedPhotos.addAll(selectedPhotos); navigate(Screen.TIMELINE) }
        }
    }
    private fun photoTile(n: Int): View {
        val shots = listOf(R.drawable.photo_tofu, R.drawable.photo_market2, R.drawable.photo_cafe2, R.drawable.photo_waves, R.drawable.photo_harbor2, R.drawable.photo_gnbeach)
        return photoCard(shots[(n - 1) % shots.size], 116, radius = 16f, selected = n in selectedPhotos, into = null, description = "예시 사진 $n") {
            if (!selectedPhotos.add(n)) selectedPhotos.remove(n); render()
        }
    }
    private fun timeline() {
        pageHeader("기록", trailing = dateLabel(draft.departure))
        map()
        if (signedInAs != null && serverTripId != null) put(label("서버에서 차의 기록을 10초마다 가져와요", 12, pMuted), gap = 8)
        driveRecord()
        put(label("방문 기록", 15, pInk, bold = true), gap = 8)
        tile("안목 커피거리", "10:32, 사진 3장", thumb = photoFor("안목 커피거리"), chevron = true) { navigate(Screen.PHOTOS) }
        tile("초당 순두부 거리", "12:34, 사진 ${recordedPhotos.size}장", thumb = photoFor("초당 순두부 거리"), chevron = true) { navigate(Screen.PHOTOS) }
        moments.forEach { m ->
            val (name, detail) = m.split('\u0001', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            tile(name, detail, iconRes = R.drawable.ic_fan_pin, trailing = pill("내 기록", pAccent))
        }
        hereSuggestion()
        space(8)
        grid(listOf(actionButton("여행 요약", primary = false) { summaryPast = null; navigate(Screen.SUMMARY) },
            actionButton("도착 알림 예시", primary = false) { navigate(Screen.ARRIVAL) }), 2, 52)
    }
    private fun hereSuggestion() {
        val found = nearby
        when {
            locating -> tile("현재 위치를 찾는 중이에요", "잠시만 기다려 주세요", iconRes = R.drawable.ic_here, dashed = true)
            found != null -> tile(found.first, found.second, eyebrow = "지금 여기 · 위치 기반 추천", thumb = photoFor(found.first), dashed = true,
                trailing = iconButton(R.drawable.ic_fan_trip, "기록에 추가", primary = true) {
                    moments.add("${found.first}\u0001${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))} · 현재 위치에서 기록")
                    nearby = null; render()
                }.apply { layoutParams = lp(dp(40), dp(40)) })
            else -> tile("지금 있는 곳 기록하기", "현재 위치로 근처 장소를 찾아요", iconRes = R.drawable.ic_here, dashed = true) { locateHere() }
        }
    }
    @SuppressLint("MissingPermission")
    private fun locateHere() {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION); return
        }
        val manager = getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).firstOrNull { manager.isProviderEnabled(it) }
            ?: run { message("휴대폰의 위치 서비스를 켜 주세요."); return }
        locating = true; render()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, mainExecutor) { onLocated(it ?: manager.getLastKnownLocation(provider)) }
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: Location) = onLocated(location)
                @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
                override fun onProviderEnabled(p: String) = Unit
                override fun onProviderDisabled(p: String) = Unit
            }, mainLooper)
        }
    }

    private fun onLocated(location: Location?) {
        if (location == null) { locating = false; nearby = null; message("현재 위치를 찾지 못했어요."); render(); return }
        val candidates = repository.places + repository.radioPlace
        // Places carry lat/lng (contract v1, optional); ones without coordinates are skipped.
        val closest = candidates.mapNotNull { place ->
            val lat = place.lat ?: return@mapNotNull null; val lng = place.lng ?: return@mapNotNull null
            val out = FloatArray(1); Location.distanceBetween(location.latitude, location.longitude, lat, lng, out); place to out[0]
        }.minByOrNull { it.second }
        if (closest != null && closest.second < 1500f) {
            val (place, meters) = closest
            locating = false
            nearby = place.name to listOf("지금 위치에서 ${distanceLabel(meters)}", place.open, "여기 들렀나요?").filter(String::isNotEmpty).joinToString(" · ")
            render(); return
        }
        // No known place nearby: name the spot from the address (Geocoder may block, so off the main thread).
        Thread {
            val address = runCatching {
                @Suppress("DEPRECATION")
                if (Geocoder.isPresent()) Geocoder(this, Locale.KOREAN).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() else null
            }.getOrNull()
            val name = address?.let { listOfNotNull(it.locality ?: it.adminArea, it.subLocality ?: it.thoroughfare).distinct().joinToString(" ") }
                ?.takeIf { it.isNotBlank() } ?: "새로 들른 곳"
            val coords = String.format(Locale.US, "%.4f, %.4f", location.latitude, location.longitude)
            runOnUiThread { locating = false; nearby = name to "$coords · 근처에 아는 장소가 없어요"; render() }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != REQUEST_LOCATION) return
        if (results.any { it == PackageManager.PERMISSION_GRANTED }) locateHere() else message("위치 권한이 있어야 지금 있는 곳을 추천할 수 있어요.")
    }

    private fun distanceLabel(meters: Float) = if (meters < 1000) "${meters.toInt()}m" else String.format(Locale.US, "%.1fkm", meters / 1000)

    private fun driveRecord() {
        if (driveEvents.isEmpty()) { put(label("차에서 저장하거나 들른 장소가 여기에 모여요.", 13, pMuted)); return }
        val finished = driveEvents.any { it.type == DriveEvent.Type.TRIP_FINISHED }
        val started = driveEvents.any { it.type == DriveEvent.Type.TRIP_STARTED }
        put(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(label("차에서 남긴 기록", 15, pInk, bold = true), lp(0, WRAP, 1f))
            addView(pill(if (finished) "주행 끝" else if (started) "주행 중" else "출발 전", pAccent))
        }, gap = 8)
        driveEvents.filter { it.placeId != null }.groupBy { it.placeId!! }.forEach { (placeId, events) ->
            val (badge, body) = when (events.last().type) {
                DriveEvent.Type.WAYPOINT_APPROVED -> "경유지" to "들렀다 가기로 한 곳"
                DriveEvent.Type.WAYPOINT_DECLINED -> "다음에" to "다음 여행 후보로 남겨 둔 곳"
                else -> "저장" to "주행 중에 기억해 둔 곳"
            }
            val name = placeName(placeId)
            tile(name, "${clock(events.first().at)}, $body", thumb = photoFor(name), trailing = pill(badge, if (badge == "경유지") pAccent else pMuted))
        }
        space(8)
    }
    private fun summary() {
        val past = summaryPast?.let { pastTrips.getOrNull(it) }
        val approved = driveEvents.filter { it.type == DriveEvent.Type.WAYPOINT_APPROVED }.mapNotNull { it.placeId }.filter { it !in itineraryIds }.distinct()
        val stops = past?.stops?.map { it.name } ?: (itineraryPlaces().map { it.name } + approved.map(::placeName))
        val photos = past?.stops?.sumOf { it.photos } ?: (3 + recordedPhotos.size)
        pageHeader(if (past != null) "${past.title} 요약" else "여행 요약", trailing = past?.dates ?: "${draft.destination} ${tripLength()}")
        // Photo collage: one tall photo and two stacked ones (v4: 1.4fr / 1fr, rows 110).
        val shots = (stops.map(::photoFor) + listOf(R.drawable.photo_cafe2, R.drawable.photo_tofu, R.drawable.photo_lakesun1)).distinct()
        put(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(roundImage(shots[0], 24f), lp(0, dp(228), 1.4f).apply { marginEnd = dp(8) })
            addView(LinearLayout(this@PhoneActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(roundImage(shots[1], 16f), lp(MATCH, dp(110)).apply { bottomMargin = dp(8) }); addView(roundImage(shots[2], 16f), lp(MATCH, dp(110)))
            }, lp(0, dp(228), 1f))
        }, gap = 16)
        val stats = if (past != null) listOf(Triple("${past.km}", "km", "이동 거리"), Triple("${stops.size}", "곳", "들른 장소"), Triple("$photos", "장", "사진"), Triple(past.who, "", "함께"))
            else listOf(Triple("214", "km", "이동 거리"), Triple("${stops.size}", "곳", "들른 장소"), Triple("$photos", "장", "사진"), Triple("2", "개", "들은 이야기"))
        grid(stats.map { (n, u, k) -> stat(n, u, k) }, 2, 92, gap = 12, bottom = 12)
        past?.stops?.maxByOrNull { it.photos }?.let { best ->
            put(label("${past.region}에서 ${stops.size}곳을 들렀어요. 사진을 가장 많이 남긴 곳은 ${best.name}(${best.photos}장)이에요.", 15, pInk, medium = true).apply {
                setPadding(dp(20), dp(18), dp(20), dp(18)); background = cardBg()
            }, gap = 12)
        }
        put(label(if (past != null) "지난 여행 기록은 예시예요." else "이동 거리와 들은 이야기 수는 예시예요.", 13, pMuted), gap = 16)
        val seconds = 12 + 15 * (stops.size + 1)
        tile("여행 영상 만들기", "장면 ${stops.size + 1}개, 약 ${seconds / 60}분 ${seconds % 60}초", iconRes = R.drawable.ic_movie, chevron = true) { filmPreview(stops, seconds) }
        tile("여행 책자 만들기", "사진 5장부터, ${2 + stops.size}쪽", iconRes = R.drawable.ic_book, chevron = true) {
            if (photos < 5) message("사진을 5장 이상 담아 주세요.") else bookPreview(stops, photos)
        }
        if (past == null) tile("공개 범위", null, iconRes = R.drawable.ic_lock, trailing = pill(visibilityLabel(), pMuted), gap = 16) { navigate(Screen.DESTINATION) }
        put(actionButton("공유하기", primary = false, icon = R.drawable.ic_share) { message("공유 기능은 연결 전이에요. 기기 밖으로 보내지 않아요.") }, gap = 8)
    }
    /** What the trip film would hold: 출발 + each stop as a scene. ponytail: preview only — making the film isn't built yet. */
    private fun filmPreview(stops: List<String>, seconds: Int) {
        val scenes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        (listOf("출발") + stops).forEachIndexed { i, name ->
            scenes.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                addView(label("${i + 1}", 13, pAccent, bold = true).apply { gravity = Gravity.CENTER; background = oval(pAccentSoft) }, lp(dp(26), dp(26)).apply { marginEnd = dp(12) })
                addView(label(name, 15, pInk, medium = true), lp(0, WRAP, 1f))
                addView(label(if (i == 0) "12초" else "15초", 13, pMuted))
            }, lp(MATCH, WRAP))
        }
        popup("여행 영상", "장면 ${stops.size + 1}개를 약 ${seconds / 60}분 ${seconds % 60}초 영상으로 묶어요. 차에서 들은 이야기가 자막으로 들어가요.", scenes,
            primary = "영상 만들기", secondary = "닫기") { it.dismiss(); message("영상 만들기는 준비 중이에요. 지금은 미리보기만 볼 수 있어요.") }
    }

    /** What the trip book would hold. ponytail: preview only — making the book isn't built yet. */
    private fun bookPreview(stops: List<String>, photos: Int) {
        val pages = listOf("표지" to "여행 이름 · 날짜 · 함께한 사람", "지도" to "다녀온 길 한 장") + stops.map { it to "사진 · 메모 · 들은 이야기" }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pages.forEachIndexed { i, (name, what) ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6))
                addView(label("${i + 1}쪽", 13, pAccent, bold = true), lp(dp(40), WRAP))
                addView(LinearLayout(this@PhoneActivity).apply { orientation = LinearLayout.VERTICAL; addView(label(name, 15, pInk, medium = true)); addView(label(what, 12, pMuted)) }, lp(0, WRAP, 1f))
            }, lp(MATCH, WRAP))
        }
        popup("여행 책자", "${pages.size}쪽, 사진 ${photos}장으로 엮어요. PDF로 받거나 인쇄할 수 있게 만들 거예요.", ScrollView(this).apply { addView(list) },
            primary = "책자 만들기", secondary = "닫기") { it.dismiss(); message("책자 만들기는 준비 중이에요. 지금은 미리보기만 볼 수 있어요.") }
    }

    // ── P14 MY · P15 recap ──────────────────────────────────────────────────

    private fun my() {
        pageHeader("MY")
        val provider = signedInAs
        tile(if (provider != null) "차곡 여행자" else "로그인하지 않았어요", provider ?: "눌러서 로그인하면 차로 여행을 보낼 수 있어요",
            leading = label(provider?.first()?.uppercase() ?: "?", 18, pInk, bold = true).apply { gravity = Gravity.CENTER; background = oval(pAccentSoft) },
            titleSize = 17, chevron = provider == null, gap = 16, onClick = if (provider == null) ({ signIn() }) else null)
        photoCard(R.drawable.photo_pavilion, 160, title = "지난 여행 돌아보기", titleSize = 17, sub = "여행 ${pastTrips.size}개의 기록, 지도, 사진",
            description = "지난 여행 돌아보기, 여행 ${pastTrips.size}개", gap = 24) { showPast(0, push = true) }
        put(label("설정", 15, pInk, bold = true), gap = 8)
        tile("기본 공개 범위", null, trailing = pill(visibilityLabel(), pMuted), minHeight = 64) { navigate(Screen.DESTINATION) }
        val located = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        tile("위치 사용", "'지금 이 장소 기록'을 누를 때만 써요", trailing = pill(if (located) "허용됨" else "꺼짐", if (located) pAccent else pMuted), minHeight = 64, gap = 24)
        put(label("연결", 15, pInk, bold = true), gap = 8)
        tile("차량 연결", if (provider != null) "누르면 차에 넣을 8자리 번호가 나와요" else "로그인하면 차량에 여행을 보낼 수 있어요", iconRes = R.drawable.ic_car,
            trailing = pill(if (provider != null) "번호 받기" else "로그인 필요", if (provider != null) pAccent else pMuted)) { if (provider == null) signIn() else pairCar() }
        tile("유튜브 연결", "본 영상 속 장소 찾기", iconRes = R.drawable.ic_fan_recap, trailing = pill("예정", pMuted), gap = 24)
        put(label("계정", 15, pInk, bold = true), gap = 8)
        put(if (provider == null) actionButton("로그인", primary = true) { signIn() } else actionButton("로그아웃", primary = false) { signOut() }, gap = 8)
    }
    /** Email/password login (POST /v1/auth/login). Accounts are made by the team admin; the app never registers. */
    private fun signIn() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val email = field("이메일", password = false)
        val password = field("비밀번호 (12자 이상)", password = true).apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        val error = label("", 13, pAccent).apply { visibility = View.GONE }
        box.addView(email, lp(MATCH, dp(54))); box.addView(password, lp(MATCH, dp(54)).apply { topMargin = dp(10) })
        box.addView(error, lp(MATCH, WRAP).apply { topMargin = dp(10) })
        val submit = { d: Dialog ->
            val id = email.text.toString().trim(); val secret = password.text.toString()
            if (id.isEmpty() || secret.length < 12) { error.text = "이메일과 12자 이상 비밀번호를 입력해 주세요."; error.visibility = View.VISIBLE }
            else {
                error.visibility = View.GONE
                remote({ api.login(id, secret) }) { token ->
                    session.edit().putString("token", token).putString("email", id).apply()
                    d.dismiss(); message("로그인했어요."); render()
                }
            }
        }
        val dialog = popup("로그인", "팀에서 받은 이메일 계정으로 로그인해요. 로그인하면 차로 여행을 보낼 수 있어요.", box, primary = "로그인", secondary = "취소", logo = true, onPrimary = submit)
        password.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) submit(dialog) } }
    }

    private fun signOut() {
        popup("로그아웃할까요?", "이 휴대폰에 남긴 기록은 그대로 있어요.", primary = "로그아웃", secondary = "취소") {
            session.edit().remove("token").remove("email").remove("tripId").apply() // 저장한 여행 stay on the phone
            it.dismiss(); render()
        }
    }

    /**
     * P09 "차로 보내기". Signed in: save the trip (POST/PUT /v1/trips) → pick the account's Pleos device → queue the
     * TripPlan (/stage). Signed out: nothing can reach the car yet, so say so (the TripPlan still goes to logcat for relay.sh).
     */
    private fun sendToCar(places: List<Place>) {
        if (signedInAs == null) {
            repository.stageTrip(draft, places) // logcat only, for the adb relay demo
            popup("아직 차와 연결되지 않았어요", "로그인하고 MY → 차량 연결에서 받은 번호를 차곡 차량 앱에 넣으면 여행을 보낼 수 있어요.",
                primary = "로그인하기", secondary = "닫기") { it.dismiss(); signIn() }
            return
        }
        message("차량으로 보내는 중…")
        // Keep the tripId as soon as it exists: if the device list fails, a retry updates this trip instead of making another.
        remote({ api.saveTrip(serverTripId, draft, places).also { session.edit().putString("tripId", it).apply() } to api.pleosDevices() }) { (tripId, cars) ->
            when (cars.size) {
                0 -> popup("아직 차와 연결되지 않았어요", "여행은 저장해 뒀어요. 번호를 받아 차곡 차량 앱 → 프로필 → 폰과 연결하기에 넣으면 바로 보낼 수 있어요.",
                    primary = "번호 받기", secondary = "닫기") { it.dismiss(); pairCar() }
                1 -> stageTo(tripId, cars[0])
                else -> {
                    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    var dialog: Dialog? = null
                    cars.forEach { car -> tile(car.second, "Pleos", trailing = label("›", 22, pMuted), gap = 8, into = list) { dialog?.dismiss(); stageTo(tripId, car) } }
                    dialog = popup("어느 차로 보낼까요?", null, list, primary = null, secondary = "취소")
                }
            }
        }
    }

    /**
     * 저장한 여행 on this phone: a JSON list in the private prefs, newest first. Entry = local id, the TripPlan, visibility,
     * savedAt, and tripId once it is saved to the account too. "currentSaved" = the entry the trip on screen came from.
     */
    private fun savedTrips(): MutableList<JSONObject> = runCatching {
        JSONArray(session.getString("savedTrips", "[]")).let { a -> MutableList(a.length()) { a.getJSONObject(it) } }
    }.getOrDefault(mutableListOf())
    private fun keepSavedTrips(list: List<JSONObject>) = session.edit().putString("savedTrips", JSONArray(list).toString()).apply()
    private fun savedPlan(entry: JSONObject) = TripPlan.fromJson(entry.getJSONObject("plan").toString())

    /**
     * 일정 저장하기: the trip goes into 저장한 여행 (MY), replacing its own earlier save; it comes back after the app is closed.
     * Signed in, it is also saved to the account (same server trip as 차로 보내기, so sending later updates it).
     */
    private fun saveTrip(places: List<Place>) {
        val id = session.getString("currentSaved", null) ?: UUID.randomUUID().toString()
        val entry = JSONObject().put("id", id).put("plan", JSONObject(TripPlan("saved", draft, places).toJson()))
            .put("visibility", draft.visibility).put("savedAt", Instant.now().toString())
        serverTripId?.let { entry.put("tripId", it) }
        keepSavedTrips(listOf(entry) + savedTrips().filter { it.optString("id") != id })
        session.edit().putString("currentSaved", id).apply()
        if (signedInAs == null) { message("저장했어요. 홈 → 다른 여행 고르기에서 볼 수 있어요."); return }
        remote({ api.saveTrip(serverTripId, draft, places) }) { tripId ->
            session.edit().putString("tripId", tripId).apply()
            keepSavedTrips(savedTrips().onEach { if (it.optString("id") == id) it.put("tripId", tripId) })
            message("저장했어요. 계정에도 저장했어요.")
        }
    }

    /** Puts a saved trip on screen: its draft and stop order, and its server trip (so 차로 보내기 sends that one). */
    private fun useSavedTrip(entry: JSONObject) {
        val plan = savedPlan(entry)
        draft = plan.draft.also { it.visibility = entry.optString("visibility", it.visibility) }
        itineraryIds.clear(); itineraryIds.addAll(plan.places.map(Place::id))
        session.edit().putString("currentSaved", entry.getString("id")).apply {
            entry.optString("tripId").takeIf(String::isNotEmpty)?.let { putString("tripId", it) } ?: remove("tripId")
        }.apply()
    }

    /** Fresh start: the trip last saved or opened comes back (after a rotation the instance state has the live edit instead). */
    private fun restoreSavedTrip() {
        // One-off: the first 저장하기 kept a single trip under "savedTrip".
        session.getString("savedTrip", null)?.let { old ->
            if (savedTrips().isEmpty()) runCatching {
                val id = UUID.randomUUID().toString()
                keepSavedTrips(listOf(JSONObject().put("id", id).put("plan", JSONObject(old)).put("visibility", JSONObject(old).optString("visibility", "나만"))))
                session.edit().putString("currentSaved", id).apply()
            }
            session.edit().remove("savedTrip").apply()
        }
        val id = session.getString("currentSaved", null) ?: return
        savedTrips().find { it.optString("id") == id }?.let { runCatching { useSavedTrip(it) }.onFailure { e -> Log.w("ChagokApi", "Ignoring a broken saved trip", e) } }
    }

    /**
     * 홈 → 다른 여행 고르기: the saved trips (newest first) to switch to, ✕ to take one off the list, and 새 여행 만들기.
     * A trip that was never saved is lost when another one is opened, so the popup says so.
     */
    private fun pickTrip() {
        var dialog: Dialog? = null
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val current = session.getString("currentSaved", null)
        val saved = savedTrips()
        if (saved.isEmpty()) list.addView(label("아직 저장한 여행이 없어요. 일정 화면에서 저장하기를 누르면 여기 모여요.", 14, pMuted), lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        saved.forEach { entry ->
            val plan = runCatching { savedPlan(entry) }.getOrNull() ?: return@forEach
            val here = entry.optString("id") == current
            val trailing = if (here) pill("지금 여행", pAccent) else label("✕", 18, pMuted).apply {
                gravity = Gravity.CENTER; minWidth = dp(40); minHeight = dp(40); contentDescription = "${plan.draft.destination} 여행 지우기"
                setOnClickListener { keepSavedTrips(savedTrips().filter { it.optString("id") != entry.optString("id") }); dialog?.dismiss(); message("목록에서 지웠어요."); pickTrip() }
            }
            tile("${plan.draft.destination} 여행", "${dateLabel(plan.draft.departure)} · ${plan.places.size}곳 · ${if (entry.has("tripId")) "계정에도 저장됨" else "이 폰에 저장됨"}",
                trailing = trailing, gap = 8, into = list, onClick = if (here) null else ({ dialog?.dismiss(); useSavedTrip(entry); render(); message("${plan.draft.destination} 여행으로 바꿨어요.") }))
        }
        tile("새 여행 만들기", "목적지부터 5단계", dashed = true, gap = 0, into = list) { dialog?.dismiss(); newTrip(); navigate(Screen.DESTINATION) }
        dialog = popup("다른 여행 고르기", if (current == null) "지금 여행은 아직 저장하지 않았어요. 다른 여행을 열기 전에 일정 화면에서 저장해 두세요." else null,
            ScrollView(this).apply { addView(list) }, primary = null, secondary = "닫기")
    }

    /** A new trip gets its own server trip: forget the last one so 차로 보내기 doesn't overwrite it. */
    private fun newTrip() { session.edit().remove("tripId").remove("currentSaved").apply() }

    /** MY → 차량 연결: an 8-digit code (5 minutes) to type into the car app's 폰과 연결하기 (POST /v1/pairings). */
    private fun pairCar() {
        remote({ api.pairingCode() }) { code ->
            val number = label("${code.take(4)} ${code.drop(4)}", 38, pAccent, bold = true).apply {
                gravity = Gravity.CENTER; letterSpacing = .12f; fontFeatureSettings = "tnum"; background = shape(pAccentSoft, null, 20f); setPadding(0, dp(16), 0, dp(16))
                contentDescription = "연결 번호 ${code.toList().joinToString(" ")}"
            }
            popup("차에 이 번호를 넣어 주세요", "차곡 차량 앱 → 프로필 → 폰과 연결하기. 5분 안에 한 번만 쓸 수 있어요.", number)
        }
    }

    private fun stageTo(tripId: String, car: Pair<String, String>) {
        remote({ api.stage(tripId, car.first) }) {
            popup("${car.second}에 보냈어요", "차가 받으면 차곡 차량 앱의 오늘 일정에 이 여행이 떠요.")
        }
    }

    /** P12: the car's DriveEvents for the trip sent from this phone (GET /v1/trips/{id}/events). */
    private fun refreshEvents() {
        val tripId = serverTripId ?: return
        if (signedInAs == null) return
        remote({ api.events(tripId) }, quiet = true) { events ->
            if (events.isEmpty() || events == driveEvents) return@remote
            driveEvents.clear(); driveEvents.addAll(events)
            if (screen == Screen.TIMELINE) render()
        }
    }

    /** Runs [work] off the main thread and hands the result back on it; errors become a short message. */
    private fun <T> remote(work: () -> T, quiet: Boolean = false, done: (T) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess(done).onFailure { failure(it, quiet) }
            }
        }.start()
    }

    private fun failure(error: Throwable, quiet: Boolean) {
        Log.w("ChagokApi", "Backend call failed", error)
        when {
            error is ApiError && error.status == 401 -> {
                val wasSignedIn = signedInAs != null
                session.edit().remove("token").apply()
                message(if (wasSignedIn) "로그인이 만료됐어요. 다시 로그인해 주세요." else "이메일이나 비밀번호가 맞지 않아요."); render()
            }
            quiet -> Unit
            error is ApiError && error.status == 429 -> message("요청이 많아요. ${error.retryAfter ?: 60}초 뒤에 다시 해 주세요.")
            error is ApiError && error.status == 422 -> message("서버가 여행 내용을 받지 않았어요: ${error.message}")
            error is ApiError -> message("서버 오류가 났어요 (${error.status}). 잠시 후 다시 해 주세요.")
            else -> message("서버에 연결하지 못했어요. 인터넷 연결을 확인해 주세요.")
        }
    }

    override fun onPause() { poller.removeCallbacks(pollEvents); super.onPause() }
    override fun onResume() {
        super.onResume()
        poller.removeCallbacks(pollEvents) // render() in onCreate may have scheduled it already
        if (screen == Screen.TIMELINE && signedInAs != null && serverTripId != null) { refreshEvents(); poller.postDelayed(pollEvents, 10_000) }
    }

    // ── P16 past trips ──────────────────────────────────────────────────────

    private class PastStop(val time: String, val name: String, val where: String, val photos: Int, val note: String = "")
    private class PastTrip(val title: String, val dates: String, val region: String, val km: Int, val who: String, val stops: List<PastStop>)

    /** ponytail: sample past trips; the trip store (BACKEND.md 기록) will return the user's own. */
    private val pastTrips = listOf(
        PastTrip("속초 1박 2일", "2026.10.11 – 10.12", "강원 속초시", 214, "친구와", listOf(
            PastStop("10.11 11:20", "영금정", "강원 속초시 영금정로", 4, "파도 소리가 컸던 정자"),
            PastStop("13:05", "속초 중앙시장", "강원 속초시 중앙로147번길", 2, "닭강정 사서 바다로"),
            PastStop("16:40", "영랑호 둘레길", "강원 속초시 영랑호반길", 5),
            PastStop("10.12 09:10", "설악산 소공원", "강원 속초시 설악산로", 3))),
        PastTrip("전주 당일", "2026.09.06", "전북 전주시", 186, "부모님과", listOf(
            PastStop("10:30", "전주 한옥마을", "전북 전주시 완산구 기린대로", 6, "경기전 앞에서 다 같이 한 장"),
            PastStop("12:40", "남부시장", "전북 전주시 완산구 풍남문1길", 1),
            PastStop("15:10", "덕진공원", "전북 전주시 덕진구 권삼득로", 3))),
        PastTrip("양평 드라이브", "2026.08.15", "경기 양평군", 92, "혼자", listOf(
            PastStop("09:50", "두물머리", "경기 양평군 양서면 양수리", 5, "물안개가 남아 있던 아침"),
            PastStop("12:20", "세미원", "경기 양평군 양서면 양수로", 2))),
    )

    private fun showPast(index: Int, push: Boolean = false) {
        if (index !in pastTrips.indices) return
        pastIndex = index
        if (push) navigate(Screen.PAST) else { shownScreen = null; render() }
    }

    private fun past() {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(backButton(), lp(dp(40), dp(40))); top.addView(View(this), lp(0, 1, 1f)); top.addView(pill("${pastIndex + 1} / ${pastTrips.size}", pMuted))
        put(top, gap = 16)
        put(label("지난 여행", 28, pInk, bold = true), gap = 16)
        content.clipChildren = false // let the pages slide across the 20dp side padding to the screen edge
        val pager = FrameLayout(this).apply { clipChildren = false }
        pager.addView(pastPage(pastIndex), FrameLayout.LayoutParams(MATCH, WRAP))
        put(pager, gap = 0); pastPager = pager

        actions.visibility = View.VISIBLE
        val turner = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        turner.addView(actionButton("이전 여행", primary = false) { settlePast(pager, -1) }.apply { isEnabled = pastIndex > 0; alpha = if (isEnabled) 1f else 0.4f },
            lp(0, dp(52), 1f).apply { marginEnd = dp(8) })
        turner.addView(actionButton("다음 여행", primary = true) { settlePast(pager, 1) }.apply { isEnabled = pastIndex < pastTrips.lastIndex; alpha = if (isEnabled) 1f else 0.4f },
            lp(0, dp(52), 1f))
        actions.addView(turner, lp(MATCH, WRAP))
    }
    private fun pastPage(index: Int): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val screenContent = content; content = page
        try {
            val trip = pastTrips[index]
            val shots = trip.stops.map { photoFor(it.name) }
            photoCard(pastCover(trip), 280, tags = listOf("${trip.km}km", "장소 ${trip.stops.size}곳", "사진 ${trip.stops.sumOf { it.photos }}장"),
                title = trip.title, sub = "${trip.dates} · ${trip.region} · ${trip.who}", gap = 12)
            val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            pastTrips.indices.forEach { i ->
                dots.addView(View(this).apply { background = shape(if (i == index) pInk else pDot, null, 3f) },
                    lp(dp(if (i == index) 20 else 6), dp(6)).apply { marginEnd = dp(6) })
            }
            put(dots, gap = 16)
            tile("여행 요약 · 영상 · 책자", "이 여행을 한눈에 보고 영상이나 책자로 만들어요", iconRes = R.drawable.ic_movie, chevron = true) { summaryPast = index; navigate(Screen.SUMMARY) }
            space(8)
            map()
            timelineCard(trip.stops.mapIndexed { i, s ->
                TimeRow(s.time, s.name, sub = listOf(s.where, s.note).filter(String::isNotEmpty).joinToString("\n"), right = "사진 ${s.photos}", photo = shots[i])
            }, meta = "다녀온 곳", tag = trip.who)
            put(label("사진 ${trip.stops.sumOf { it.photos }}장 · 예시", 13, pMuted), gap = 8)
            grid(trip.stops.flatMap { s -> List(s.photos) { s.name } }.take(9).mapIndexed { i, name ->
                roundImage(listOf(photoFor(name), R.drawable.photo_waves, R.drawable.photo_lakesun2)[i % 3], 16f).apply { contentDescription = "$name 사진 예시" }
            }, 3, 96)
            put(label("옆으로 밀거나 아래 버튼으로 다른 여행을 볼 수 있어요. 기록은 예시예요.", 13, pMuted))
        } finally { content = screenContent }
        return page
    }
    private class Slide(val dark: Boolean, val kicker: String, val big: String, val caption: String, val items: List<String> = emptyList(), val orb: Boolean = false,
                        val photo: Int? = null, val tags: List<String> = emptyList())

    private fun recapSlides(): List<Slide> {
        val itinerary = itineraryPlaces().map { it.name }
        val inCar = driveEvents.filter { it.type == DriveEvent.Type.PLACE_SAVED || it.type == DriveEvent.Type.WAYPOINT_APPROVED }
            .mapNotNull { it.placeId }.distinct().map(::placeName)
        val mine = moments.map { it.substringBefore('\u0001') }
        val visited = (itinerary + inCar + mine).distinct()
        val photos = 3 + recordedPhotos.size
        val who = draft.companions.firstOrNull() ?: "혼자"
        return listOf(
            Slide(true, "차곡 · ${draft.departure.take(4)}", "이번 여행을\n한눈에", "화면 오른쪽을 누르면 다음 장으로 넘어가요", orb = true, photo = photoFor(draft.destination)),
            Slide(false, "함께 달린 거리", "214km", "${draft.destination} ${tripLength()} 여행 · 거리는 예시예요"),
            Slide(true, "들른 장소", "${visited.size}곳", "일정·차·직접 남긴 기록을 모았어요", visited.take(4), photo = visited.firstOrNull()?.let(::photoFor)),
            Slide(false, "차에서 기억해 둔 곳", if (inCar.isEmpty()) "아직 없어요" else "${inCar.size}곳",
                if (inCar.isEmpty()) "주행 중 '이 장소 기억해 줘'라고 하면 여기에 모여요" else "주행 중에 저장하거나 들르기로 한 곳", inCar.take(4)),
            Slide(true, "남긴 순간", "${photos}장", "사진 ${photos}장 · 직접 남긴 기록 ${mine.size}곳", photo = R.drawable.photo_tofu),
            Slide(false, "이번 여행의 취향", "${draft.mood}\n여행자", "$who${withParticle(who).takeIf { who != "혼자" } ?: ""} 함께 · ${draft.density} 일정", tags = draft.interests.toList()),
            Slide(true, "다음 여행도 차곡", "또 어디로\n갈까요?", "기록은 이 휴대폰에만 있어요. 공유는 연동 예정이에요.", orb = true, photo = R.drawable.photo_lakesun2),
        )
    }
    private fun recap() {
        val slides = recapSlides()
        recapIndex = recapIndex.coerceIn(0, slides.lastIndex)
        val slide = slides[recapIndex]
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(backButton(), lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
        top.addView(steps(recapIndex + 1, slides.size, "${slides.size}장 중 ${recapIndex + 1}번째"), lp(0, WRAP, 1f))
        put(top, gap = 16)

        // Photo slides are white type on the picture; the others sit on a plain white card.
        val onPhoto = slide.photo != null
        val fg = if (onPhoto) Color.WHITE else pInk
        val card = FrameLayout(this).apply { background = shape(pSurface, null, 24f); clipToOutline = true }
        slide.photo?.let {
            card.addView(roundImage(it, 24f), FrameLayout.LayoutParams(MATCH, MATCH))
            card.addView(View(this).apply { background = scrim() }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (slide.orb) body.addView(mark(64), lp(dp(64), dp(64)).apply { bottomMargin = dp(16) })
        body.addView(label(slide.big, 44, fg, bold = true).apply { setLineSpacing(0f, 1.05f); fontFeatureSettings = "tnum"; typeface = num(650) }, lp(MATCH, WRAP))
        body.addView(label(slide.kicker, 17, fg, bold = true), lp(MATCH, WRAP).apply { topMargin = dp(8) })
        body.addView(label(slide.caption, 14, if (onPhoto) Color.argb(217, 255, 255, 255) else pMuted), lp(MATCH, WRAP).apply { topMargin = dp(4) })
        slide.items.forEach { item ->
            body.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(roundImage(photoFor(item), 12f), lp(dp(40), dp(40)).apply { marginEnd = dp(12) }); addView(label(item, 15, fg, bold = true))
            }, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        if (slide.tags.isNotEmpty()) body.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL
            slide.tags.forEach { addView(pill(it, pAccent), lp(WRAP, WRAP).apply { marginEnd = dp(8) }) } }, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        card.addView(body, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(24), 0, dp(24), dp(28)) })
        // Tap zones over the card: left third goes back, the rest goes forward.
        val zones = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        zones.addView(View(this).apply { contentDescription = "이전 장"; isClickable = true; setOnClickListener { if (recapIndex > 0) { recapIndex--; render() } } }, lp(0, MATCH, 1f))
        zones.addView(View(this).apply { contentDescription = "다음 장"; isClickable = true; setOnClickListener { if (recapIndex < slides.lastIndex) { recapIndex++; render() } } }, lp(0, MATCH, 2f))
        card.addView(zones, FrameLayout.LayoutParams(MATCH, MATCH))
        content.addView(card, lp(MATCH, resources.displayMetrics.heightPixels - dp(if (recapIndex == slides.lastIndex) 290 else 200)))

        if (recapIndex == slides.lastIndex) {
            primary("새 여행 만들기") { newTrip(); navigate(Screen.DESTINATION) }
            textButton("처음부터 다시 보기", center = true, into = actions) { recapIndex = 0; render() }
        }
    }
    // ── chrome ──────────────────────────────────────────────────────────────

    private fun plusBar() {
        val bar = findViewById<LinearLayout>(R.id.bottom_navigation); bar.removeAllViews()
        bar.visibility = if (screen in plusScreens) View.VISIBLE else View.GONE
        bar.addView(ImageButton(this, null, 0).apply {
            setImageResource(R.drawable.ic_fan_trip); imageTintList = ColorStateList.valueOf(Color.WHITE); scaleType = ImageView.ScaleType.CENTER
            background = oval(pInk); foreground = ripple(28f); elevation = dpf(6f); outlineSpotShadowColor = pInk
            contentDescription = "무엇을 할까요? 새 여행, 추천, 기록, 모아보기"
            setOnClickListener { if (screen == Screen.START) onBackPressed() else navigate(Screen.START) }
        }, lp(dp(56), dp(56)))
    }
    private fun pageHeader(title: String, trailing: String? = null, sub: String? = null, profile: Boolean = false) {
        if (screen != Screen.HOME) putBack(gap = 16)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(title, 28, pInk, bold = true), lp(0, WRAP, 1f))
        trailing?.let { row.addView(pill(it, pMuted), lp(WRAP, WRAP).apply { marginStart = dp(8) }) }
        if (profile) row.addView(iconButton(R.drawable.ic_person, "MY · 내 정보") { navigate(Screen.MY) }, lp(dp(40), dp(40)).apply { marginStart = dp(8) })
        put(row, gap = if (sub == null) 16 else 4)
        sub?.let { put(label(it, 13, pMuted), gap = 16) }
    }
    private fun wizardHeader(step: Int, name: String, title: String, sub: String? = null) {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(backButton(), lp(dp(40), dp(40)).apply { marginEnd = dp(12) })
        top.addView(steps(step, 5, "5단계 중 ${step}단계, $name"), lp(0, WRAP, 1f).apply { marginEnd = dp(12) })
        top.addView(label("$step/5", 13, pMuted).apply { fontFeatureSettings = "tnum"; typeface = num(650) })
        put(top, gap = 24)
        put(label(title, 28, pInk, bold = true), gap = if (sub == null) 16 else 4)
        sub?.let { put(label(it, 13, pMuted), gap = 16) }
    }
    private fun map() { put(layoutInflater.inflate(R.layout.panel_map, content, false)) }

    // ── components ──────────────────────────────────────────────────────────

    /**
     * v4 list item (.item): white, 24dp corners, min 76dp; a 52dp leading thumb (photo, 16dp corners), icon box or custom view;
     * title 15 bold + caption 13; trailing view, a check (selection) or a chevron. Selected = 2dp ink ring; dashed = clear with a s3 ring.
     */
    private fun tile(
        title: String, sub: String? = null, eyebrow: String? = null, trailing: View? = null, icon: Boolean = false, iconRes: Int? = null,
        thumb: Int? = null, leading: View? = null, dashed: Boolean = false, selected: Boolean? = null, chevron: Boolean = false, minHeight: Int = 76,
        gap: Int = 8, into: LinearLayout = content, titleSize: Int = 15, titleColor: Int = pInk, titleBold: Boolean = true, onClick: (() -> Unit)? = null,
    ): LinearLayout {
        val on = selected == true
        val lead = leading ?: thumb?.let { roundImage(it, 16f) } ?: if (icon || iconRes != null) icbox(iconRes) else null
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(minHeight)
            setPadding(dp(if (lead != null) 12 else 20), dp(12), dp(16), dp(12))
            background = when {
                dashed -> shape(Color.TRANSPARENT, pDot, 24f)
                on -> shape(pSurface, pInk, 24f, strokeDp = 2f)
                else -> shape(pSurface, null, 24f)
            }
            if (onClick != null) { isClickable = true; isFocusable = true; foreground = ripple(24f); setOnClickListener { onClick() } }
            if (selected != null) { isSelected = on; contentDescription = listOfNotNull(title, sub, if (on) "선택됨" else "선택 안 됨").joinToString(", ") }
        }
        lead?.let { row.addView(it, lp(dp(52), dp(52)).apply { marginEnd = dp(14) }) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        eyebrow?.let { texts.addView(label(it, 12, pMuted)) }
        texts.addView(label(title, titleSize, titleColor, bold = titleBold))
        sub?.let { texts.addView(label(it, 13, pMuted)) }
        row.addView(texts, lp(0, WRAP, 1f))
        trailing?.let { row.addView(it, (it.layoutParams as? LinearLayout.LayoutParams ?: lp(WRAP, WRAP)).apply { marginStart = dp(10) }) }
        if (selected != null) row.addView(checkMark(on), lp(dp(28), dp(28)).apply { marginStart = dp(10) })
        if (chevron) row.addView(glyph(R.drawable.ic_chevron, pMuted), lp(dp(22), dp(22)).apply { marginStart = dp(8) })
        put(row, gap, into)
        return row
    }
    private fun option(title: String, sub: String, selected: Boolean, icon: Boolean = false, onClick: () -> Unit) =
        tile(title, sub, icon = icon, selected = selected, minHeight = 64, onClick = onClick)
    /** v4 .seg: s3 track, 4dp inset; the chosen one is a white pill with dark text. */
    private fun segmented(labels: List<String>, selected: (String) -> Boolean, display: (String) -> String = { it }, onClick: (String) -> Unit): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(4), dp(4), dp(4)); background = shape(pDot, null, 999f) }
        labels.forEach { value ->
            val on = selected(value)
            bar.addView(Button(this, null, 0).apply {
                text = display(value); textSize = 15f; gravity = Gravity.CENTER; isSelected = on; typeface = font(700)
                setTextColor(if (on) pInk else pMuted); background = if (on) shape(pSurface, null, 999f) else null; foreground = ripple(999f)
                contentDescription = "${display(value)}${if (on) ", 선택됨" else ""}"
                setOnClickListener { onClick(value) }
            }, lp(0, dp(40), 1f))
        }
        return bar
    }
    /** v4 .chip: 44dp white pill; chosen = dark pill with white text. */
    private fun chipFlow(labels: List<String>, selected: (String) -> Boolean, onClick: (String) -> Unit): View {
        val flow = FlowLayout(this, dp(8))
        labels.forEach { value ->
            val on = selected(value)
            flow.addView(Button(this, null, 0).apply {
                text = value; textSize = 15f; gravity = Gravity.CENTER; minHeight = dp(44); setPadding(dp(18), 0, dp(18), 0); typeface = font(700)
                setTextColor(if (on) Color.WHITE else pInk); background = shape(if (on) pInk else pSurface, null, 999f)
                foreground = ripple(999f); isSelected = on; contentDescription = "$value${if (on) ", 선택됨" else ""}"
                setOnClickListener { onClick(value) }
            }, ViewGroup.LayoutParams(WRAP, WRAP))
        }
        return flow
    }
    private fun field(name: String, view: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(name, 12, pMuted), lp(WRAP, WRAP).apply { bottomMargin = dp(5) })
        addView(view, lp(MATCH, WRAP))
    }

    private fun valueBox(value: String, muted: Boolean = false, onClick: () -> Unit) = fieldButton("", value, muted, onClick)
    private fun primary(text: String, onClick: () -> Unit) {
        actions.visibility = View.VISIBLE
        actions.addView(actionButton(text, primary = true, onClick = onClick), lp(MATCH, dp(58)).apply { setMargins(dp(2), dp(4), dp(2), dp(4)) })
    }

    /** v4 .btn: 52dp pill, 16 bold. Primary = dark (#101317) with white text; secondary = s2 with dark text. */
    private fun actionButton(text: String, primary: Boolean, icon: Int? = null, onClick: () -> Unit) = Button(this, null, 0).apply {
        this.text = text; textSize = 16f; gravity = Gravity.CENTER; typeface = font(700); minHeight = dp(52); setPadding(dp(22), 0, dp(22), 0)
        setTextColor(if (primary) Color.WHITE else pInk)
        background = shape(if (primary) pInk else pFill, null, 999f); foreground = ripple(999f)
        icon?.let {
            setCompoundDrawablesRelative(getDrawable(it)?.mutate()?.apply { setBounds(0, 0, dp(20), dp(20)) }, null, null, null)
            compoundDrawablePadding = dp(8); compoundDrawableTintList = ColorStateList.valueOf(if (primary) Color.WHITE else pInk)
        }
        setOnClickListener { onClick() }
    }
    /** v4 back control: 40dp s2 circle with a chevron, on every screen but home; with no history it returns home. */
    private fun backButton() = iconButton(R.drawable.ic_back, "이전 화면으로") { goBack() }
    private fun goBack() {
        if (history.isNotEmpty()) onBackPressed() else { screen = Screen.HOME; render() }
    }

    /** Quiet text action. attach = false when the caller places it itself (header rows). */
    private fun textButton(text: String, center: Boolean = false, attach: Boolean = true, into: LinearLayout = content, onClick: () -> Unit): Button {
        val button = Button(this, null, 0).apply {
            this.text = text; textSize = 13f; setTextColor(pMuted); minHeight = dp(44); setPadding(dp(4), 0, dp(4), 0)
            gravity = if (center) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START; foreground = ripple(8f)
            setOnClickListener { onClick() }
        }
        if (attach) put(button, gap = 0, into = into)
        return button
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false, medium: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(2).toFloat(), 1f)
        // v4 word-break: keep-all — Korean wraps between words, not inside one (API 33+).
        textLocale = Locale.KOREAN // phrase breaking needs the text's language, whatever the device language is
        if (Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        typeface = when { bold -> font(if (size >= 24) 800 else 700); medium -> font(600); else -> font(400) }
    }

    /** v4 .tag: 30dp pill, 13 bold. Accent = dark pill with white text; otherwise s2 with grey text. */
    private fun pill(value: String, color: Int) = label(value, 13, if (color == pAccent) Color.WHITE else pMuted, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = dp(30); setPadding(dp(12), 0, dp(12), 0); background = shape(if (color == pAccent) pInk else pFill, null, 999f)
    }
    private class TimeRow(val time: String, val title: String, val sub: String? = null, val right: String? = null, val description: String? = null, val photo: Int? = null)

    /**
     * v4 trip card (.card with rows): head = meta caption + mood tag; each stop = 52dp photo · name + caption · time (tabular) + note.
     * [onMove] adds the ≡ drag handle (see dragHandle). [foot]/[action] add a caption line under a hairline.
     */
    private fun timelineCard(rows: List<TimeRow>, meta: String? = null, tag: String? = null, foot: String? = null,
                             onMove: ((from: Int, to: Int) -> Unit)? = null, action: String? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val rowViews = mutableListOf<LinearLayout>()
        val side = if (onMove != null) 8 else 0
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20 - side), dp(20), dp(20 - side), dp(16)); background = cardBg()
            if (onClick != null) { isClickable = true; foreground = ripple(24f); setOnClickListener { onClick() } }
        }
        if (meta != null || tag != null) card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(side), 0, dp(side), 0)
            addView(label(meta.orEmpty(), 13, pMuted), lp(0, WRAP, 1f)); tag?.let { addView(pill(it, pAccent)) }
        }, lp(MATCH, WRAP).apply { bottomMargin = dp(12) })
        rows.forEach { r ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(64); setPadding(dp(side), dp(6), dp(side), dp(6))
                contentDescription = r.description ?: listOfNotNull(r.time, r.title, r.sub, r.right).joinToString(", ")
            }
            rowViews += row
            r.photo?.let { row.addView(roundImage(it, 16f), lp(dp(52), dp(52)).apply { marginEnd = dp(12) }) }
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(r.title, 15, pInk, bold = true)); r.sub?.let { addView(label(it, 13, pMuted)) }
            }, lp(0, WRAP, 1f))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.END
                addView(label(r.time, 19, pInk, bold = true).apply { fontFeatureSettings = "tnum"; typeface = num(650) })
                r.right?.let { addView(label(it, 13, pMuted).apply { fontFeatureSettings = "tnum"; typeface = num(650) }) }
            }, lp(WRAP, WRAP).apply { marginStart = dp(8) })
            if (onMove != null && rows.size > 1) dragHandle(row, rowViews, onMove)
            card.addView(row, lp(MATCH, WRAP))
        }
        if (foot != null || action != null) {
            card.addView(View(this).apply { setBackgroundColor(pLine) }, lp(MATCH, dp(1)).apply { topMargin = dp(10); marginStart = dp(side); marginEnd = dp(side) })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(side), 0, dp(side), 0)
                addView(label(foot.orEmpty(), 13, pMuted), lp(0, WRAP, 1f))
                action?.let { addView(label(it, 13, pInk, bold = true), lp(WRAP, WRAP).apply { marginStart = dp(8) }) }
            }, lp(MATCH, WRAP).apply { topMargin = dp(12) })
        }
        put(card, gap = 12)
        return card
    }
    /** v4 cards have no rail (photos instead); kept so the drag code can call it. */
    private fun paintRail(rows: List<View>) = Unit
    /**
     * Drag to reorder with the ≡ handle. Press: the row lifts as a white card (shadow, 3% larger, a tick of vibration) and its
     * own rail hides, so the line behind stays whole. Move: it follows the finger inside the card; the rows it passes slide
     * aside (tick on each change). Release: it glides into its slot and only the row order changes — no screen rebuild, so
     * nothing flashes. TalkBack gets 위로/아래로 이동 actions instead.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun dragHandle(row: LinearLayout, rowViews: MutableList<LinearLayout>, onMove: (Int, Int) -> Unit) {
        val ease = DecelerateInterpolator()
        var startY = 0f; var from = 0; var target = 0
        fun center(v: View) = v.top + v.height / 2f
        // Put the row at [to] for real: reorder the card's children (positions reset), repaint the rail, update the data.
        fun commit(to: Int) {
            val card = row.parent as? LinearLayout ?: return
            val first = card.indexOfChild(rowViews[0])
            rowViews.forEach { it.animate().cancel(); it.translationY = 0f }
            rowViews.removeAt(from); rowViews.add(to, row)
            rowViews.forEach { card.removeView(it) }
            rowViews.forEachIndexed { i, v -> card.addView(v, first + i) }
            row.background = null; row.translationZ = 0f; row.scaleX = 1f; row.scaleY = 1f
            paintRail(rowViews)
            if (to != from) onMove(from, to)
        }
        row.addView(label("≡", 22, pMuted).apply {
            gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnTouchListener { handle, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        handle.parent.requestDisallowInterceptTouchEvent(true) // keep the page from scrolling
                        startY = e.rawY; from = rowViews.indexOf(row); target = from
                        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        @Suppress("UNCHECKED_CAST") (row.tag as? Triple<View, View, View>)?.let { it.first.setBackgroundColor(Color.TRANSPARENT); it.third.setBackgroundColor(Color.TRANSPARENT) }
                        row.background = shape(pSurface, pLine, 16f, strokeDp = 1f)
                        row.animate().scaleX(1.03f).scaleY(1.03f).translationZ(dpf(12f)).setInterpolator(ease).setDuration(150).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val h = row.height.toFloat()
                        val dy = (e.rawY - startY).coerceIn(rowViews.first().top - row.top.toFloat(), rowViews.last().bottom - row.bottom.toFloat())
                        row.translationY = dy
                        // Leading edge decides: going down, the row's bottom passes a neighbour's middle; going up, its top does.
                        // (Its centre can't reach the last row's middle when that row is shorter — the drag is clamped to the card.)
                        val next = from + rowViews.withIndex().count { (j, v) -> j > from && center(v) < row.bottom + dy } -
                            rowViews.withIndex().count { (j, v) -> j < from && center(v) > row.top + dy }
                        if (next != target) {
                            target = next; row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            rowViews.forEachIndexed { j, other ->
                                if (other !== row) other.animate().translationY(when { j in from + 1..target -> -h; j in target until from -> h; else -> 0f })
                                    .setInterpolator(ease).setDuration(180).start()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val slot = when {
                            target > from -> rowViews.subList(from + 1, target + 1).sumOf { it.height }.toFloat()
                            target < from -> -rowViews.subList(target, from).sumOf { it.height }.toFloat()
                            else -> 0f
                        }
                        val to = target
                        row.animate().translationY(slot).scaleX(1f).scaleY(1f).translationZ(0f).setInterpolator(ease).setDuration(180)
                            .withEndAction { commit(to) }.start()
                    }
                }
                true
            }
        }, lp(dp(44), dp(44)).apply { marginStart = dp(4) })
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val i = rowViews.indexOf(row)
                if (i > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.move_up, "위로 이동"))
                if (i < rowViews.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.move_down, "아래로 이동"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val step = when (action) { R.id.move_up -> -1; R.id.move_down -> 1; else -> return super.performAccessibilityAction(host, action, args) }
                from = rowViews.indexOf(row)
                val to = (from + step).coerceIn(0, rowViews.lastIndex)
                window.decorView.post { commit(to) } // not inside the accessibility dispatch
                return true
            }
        }
    }

    /**
     * Chagok popup (replaces the system AlertDialog): white card, 28dp corners, title + text, optional [extra] view,
     * then a deep primary pill and a quiet secondary one. [onPrimary] gets the dialog so it can stay open (form errors).
     */
    private fun popup(title: String, body: CharSequence? = null, extra: View? = null, primary: String? = "확인", secondary: String? = null,
                      logo: Boolean = false, onPrimary: (Dialog) -> Unit = { it.dismiss() }): Dialog {
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(26), dp(24), dp(18)); background = shape(pSurface, null, 28f) }
        // Logo guide: login uses the vertical lockup (symbol + 차곡 + CHAGOK), at least 96px tall.
        if (logo) card.addView(ImageView(this).apply { setImageResource(R.drawable.logo_vertical); contentDescription = "차곡" },
            lp(MATCH, dp(104)).apply { bottomMargin = dp(20); topMargin = dp(6) })
        card.addView(label(title, 20, pInk, bold = true).apply { if (logo) gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        body?.let { card.addView(TextView(this).apply { text = it; textSize = 15f; setTextColor(pMuted); setLineSpacing(dp(3).toFloat(), 1f) }, lp(MATCH, WRAP).apply { topMargin = dp(8) }) }
        extra?.let { card.addView(it, lp(MATCH, WRAP).apply { topMargin = dp(18) }) }
        primary?.let { card.addView(actionButton(it, primary = true) { onPrimary(dialog) }, lp(MATCH, dp(54)).apply { topMargin = dp(22) }) }
        secondary?.let { card.addView(actionButton(it, primary = false) { dialog.dismiss() }.apply { background = null; setTextColor(pMuted) }, lp(MATCH, dp(48)).apply { topMargin = dp(4) }) }
        dialog.setContentView(card)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(resources.displayMetrics.widthPixels - dp(40), ViewGroup.LayoutParams.WRAP_CONTENT) }
        dialog.show()
        return dialog
    }

    /** Not connected yet (YouTube import etc.): same popup instead of the shared "데모 · 연동 전" alert. */
    private fun soon(result: IntegrationResult) = popup("곧 연결돼요", when (result) { is IntegrationResult.Demo -> result.message; is IntegrationResult.Unavailable -> result.message })

    private fun field(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint; setHintTextColor(pMuted); setTextColor(pInk); textSize = 16f; setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        background = shape(pFill, null, 16f); setPadding(dp(18), 0, dp(18), 0); minHeight = dp(54)
    }

    /** 차곡 R4 symbol (logo kit 차곡.zip) as a still mark: not announced. [badge] = on a white disc, for dark surfaces. */
    private fun orb(badge: Boolean = false) = ImageView(this).apply {
        setImageResource(R.drawable.logo_symbol); scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        if (badge) { background = oval(Color.WHITE); setPadding(dp(9), dp(9), dp(9), dp(9)) }
    }

    private fun emphasize(parts: List<Pair<String, Boolean>>) = SpannableStringBuilder().apply {
        parts.forEach { (text, strong) ->
            val start = length; append(text)
            if (strong) {
                setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(pAccent), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** v4 .check: 28dp circle; on = dark with a white tick, off = a 2dp s3 ring. */
    private fun checkMark(on: Boolean = true): View = FrameLayout(this).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background = if (on) oval(pInk) else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dp(2), pDot) }
        if (on) addView(glyph(R.drawable.ic_check, Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
    }
    /** Fixed vertical gap. A bare View with WRAP_CONTENT would swallow the remaining height of the filled viewport. */
    private fun space(height: Int) { content.addView(View(this), lp(MATCH, dp(height))) }

    private fun put(view: View, gap: Int = 14, into: LinearLayout = content) { into.addView(view, lp(MATCH, WRAP).apply { bottomMargin = dp(gap) }) }

    // ── v4 parts (차곡 디자인 v4: cards 24, thumbs 16, pills, circles; one dark accent) ──

    private val placePhotos by lazy {
        mapOf(
            "강릉" to R.drawable.photo_gnbeach, "속초" to R.drawable.photo_harbor1, "양양" to R.drawable.photo_surf,
            "안목 커피거리" to R.drawable.photo_cafe2, "초당 순두부 거리" to R.drawable.photo_tofu, "경포 호수 산책길" to R.drawable.photo_lakesun1,
            "정동진 일출" to R.drawable.photo_lighthouse1, "경포대" to R.drawable.photo_waves, "영금정" to R.drawable.photo_pavilion,
            "속초 중앙시장" to R.drawable.photo_market2, "영랑호 둘레길" to R.drawable.photo_lakesun2, "설악산 소공원" to R.drawable.photo_rock,
            "전주 한옥마을" to R.drawable.photo_pavilion, "남부시장" to R.drawable.photo_market2, "덕진공원" to R.drawable.photo_lakesun1,
            "두물머리" to R.drawable.photo_lakesun2, "세미원" to R.drawable.photo_waves,
        )
    }

    /** Sample photo for a place or city (the photos the design canvas uses); unknown names get a stable fallback. */
    private fun photoFor(name: String): Int = placePhotos[name] ?: listOf(R.drawable.photo_waves, R.drawable.photo_lakesun2, R.drawable.photo_harbor1,
        R.drawable.photo_gnbeach, R.drawable.photo_cafe2, R.drawable.photo_market2)[Math.floorMod(name.hashCode(), 6)]

    private fun pastCover(trip: PastTrip) = when { trip.region.startsWith("강원 속초") -> R.drawable.photo_sokcho; trip.region.startsWith("전북") -> R.drawable.photo_pavilion; else -> R.drawable.photo_lakesun2 }

    private fun whoShort() = draft.companions.firstOrNull() ?: "혼자"

    private fun glyph(res: Int, tint: Int = pInk) = ImageView(this).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(tint); scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** v4 .ibtn: 40dp circle (s2, or dark when [primary]) with a 22dp icon. */
    private fun iconButton(res: Int, description: String, primary: Boolean = false, onClick: () -> Unit) = ImageButton(this, null, 0).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(if (primary) Color.WHITE else pInk); scaleType = ImageView.ScaleType.CENTER
        background = oval(if (primary) pInk else pFill); foreground = ripple(20f); contentDescription = description
        setOnClickListener { onClick() }
    }

    /** The back circle on its own row (put() would stretch it). */
    private fun putBack(gap: Int) = put(LinearLayout(this).apply { addView(backButton(), lp(dp(40), dp(40))) }, gap = gap)

    /** Photo clipped to [radius] (cards 24, thumbs 16). */
    private fun roundImage(res: Int, radius: Float) = ImageView(this).apply {
        setImageResource(res); scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(pDot, null, radius); clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun icbox(res: Int?) = FrameLayout(this).apply {
        background = shape(pFill, null, 16f); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        res?.let { addView(glyph(it), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER)) }
    }

    /** v4 .scrim: dark at the top and bottom of a photo so white type reads. */
    private fun scrim() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.argb(89, 7, 9, 12), Color.argb(0, 7, 9, 12), Color.argb(0, 7, 9, 12), Color.argb(209, 7, 9, 12)))

    private fun glassTag(text: String) = label(text, 13, Color.WHITE, bold = true).apply {
        gravity = Gravity.CENTER; minHeight = dp(30); setPadding(dp(12), 0, dp(12), 0); background = shape(Color.argb(122, 10, 12, 16), null, 999f)
    }

    /** v4 voice mark: the dark circle with the white 차곡 symbol. */
    private fun mark(size: Int) = ImageView(this).apply {
        setImageResource(R.drawable.logo_symbol); imageTintList = ColorStateList.valueOf(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER
        background = oval(pInk); val pad = dp((size * 0.2f).toInt()); setPadding(pad, pad, pad, pad); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * v4 .photo card: picture with the scrim, glass tags at the top, the mark / greeting / title / caption at the bottom in white.
     * [selected] draws the dark ring and check (photo picker, mood). [into] = null returns it unplaced (grids).
     */
    private fun photoCard(res: Int, height: Int, radius: Float = 24f, tags: List<String> = emptyList(), title: String? = null, titleSize: Int = 22,
                          sub: String? = null, withMark: Boolean = false, hello: String? = null, selected: Boolean? = null, description: String? = null,
                          into: LinearLayout? = content, gap: Int = 16, onClick: (() -> Unit)? = null): FrameLayout {
        val card = FrameLayout(this).apply {
            background = shape(pDot, null, radius); clipToOutline = true
            contentDescription = description ?: listOfNotNull(title, sub, selected?.let { if (it) "선택됨" else "선택 안 됨" }).joinToString(", ").ifEmpty { null }
            if (onClick != null) { isClickable = true; isFocusable = true; setOnClickListener { onClick() } }
            isSelected = selected == true
        }
        card.addView(roundImage(res, radius), FrameLayout.LayoutParams(MATCH, MATCH))
        card.addView(View(this).apply { background = scrim() }, FrameLayout.LayoutParams(MATCH, MATCH))
        val margin = dp(if (radius < 20f) 12 else 20)
        if (tags.isNotEmpty()) card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; tags.forEach { addView(glassTag(it), lp(WRAP, WRAP).apply { marginEnd = dp(6) }) }
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(if (radius < 20f) 10 else 16), dp(if (radius < 20f) 10 else 16), 0, 0) })
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (withMark) bottom.addView(mark(32), lp(dp(32), dp(32)).apply { bottomMargin = dp(12) })
        hello?.let { bottom.addView(label(it, 26, Color.WHITE, bold = true).apply { setLineSpacing(0f, 1.2f) }) }
        title?.let { bottom.addView(label(it, titleSize, Color.WHITE, bold = true)) }
        sub?.let { bottom.addView(label(it, 13, Color.argb(217, 255, 255, 255))) }
        if (bottom.childCount > 0) card.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(margin, 0, margin, margin) })
        if (selected == true) {
            card.foreground = shape(Color.TRANSPARENT, pInk, radius, strokeDp = 3f)
            card.addView(checkMark(true), FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
        } else if (onClick != null) card.foreground = ripple(radius)
        into?.addView(card, lp(MATCH, dp(height)).apply { bottomMargin = dp(gap) })
        return card
    }

    /** Equal columns (v4 .grid2/.grid3): [cells] in rows of [cols], [height]dp tall, [gap]dp apart. */
    private fun grid(cells: List<View>, cols: Int, height: Int, gap: Int = 8, into: LinearLayout = content, bottom: Int = 16) {
        val rows = cells.chunked(cols)
        rows.forEachIndexed { r, row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, v -> line.addView(v, lp(0, dp(height), 1f).apply { if (i > 0) marginStart = dp(gap) }) }
            repeat(cols - row.size) { line.addView(View(this), lp(0, dp(height), 1f).apply { marginStart = dp(gap) }) }
            into.addView(line, lp(MATCH, WRAP).apply { bottomMargin = dp(if (r == rows.lastIndex) bottom else gap) })
        }
    }

    /** v4 .field as a button: 56dp white pill, caption then the value in bold. */
    private fun fieldButton(caption: String, value: String, muted: Boolean = false, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0)
        background = shape(pSurface, null, 999f); isClickable = true; isFocusable = true; foreground = ripple(28f); contentDescription = "$caption $value"
        if (caption.isNotEmpty()) addView(label(caption, 13, pMuted), lp(WRAP, WRAP).apply { marginEnd = dp(12) })
        addView(label(value, 15, if (muted) pMuted else pInk, bold = true).apply { fontFeatureSettings = "tnum"; typeface = num(650) })
        setOnClickListener { onClick() }
    }

    /** v4 .stat: white card, big tabular number (28) + unit, caption under it. */
    private fun stat(n: String, unit: String, caption: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = cardBg()
        addView(TextView(this@PhoneActivity).apply { text = fig(listOf(n to true, unit to false), big = 28, small = 15) })
        addView(label(caption, 13, pMuted, bold = true), lp(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    /** v4 .steps: equal 4dp pill bars, the first [on] dark. */
    private fun steps(on: Int, of: Int, description: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; contentDescription = description
        (1..of).forEach { i -> addView(View(this@PhoneActivity).apply { background = shape(if (i <= on) pInk else pDot, null, 999f) }, lp(0, dp(4), 1f).apply { if (i < of) marginEnd = dp(6) }) }
    }

    /** v4 .fig: numbers big and bold in ink, the words around them small and grey. */
    private fun fig(parts: List<Pair<String, Boolean>>, big: Int = 18, small: Int = 14) = SpannableStringBuilder().apply {
        parts.forEach { (text, number) ->
            val start = length; append(text)
            if (number) {
                setSpan(TypefaceSpan(num(650)), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(AbsoluteSizeSpan(big, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(pInk), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                setSpan(AbsoluteSizeSpan(small, true), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(pMuted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // ── drawing ─────────────────────────────────────────────────────────────

    private fun shape(fill: Int, stroke: Int?, radius: Float, dashed: Boolean = false, strokeDp: Float = 1.5f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(radius)
        if (stroke != null) { val w = dpf(strokeDp).toInt().coerceAtLeast(1); if (dashed) setStroke(w, stroke, dpf(6f), dpf(4f)) else setStroke(w, stroke) }
    }
    private fun oval(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
    /** Panel card: translucent white, 20dp corners, no border (the shadow does the separating). */
    /** v4 .card: white, 24dp corners, flat. */
    private fun cardBg() = shape(pSurface, null, 24f)
    private fun lift(view: View) = Unit // v4 surfaces are flat
    private fun medium(): Typeface = font(600)
    // 차곡 디자인 v4 type: SUIT for text (res/font/suit.xml, 400/600/700/800), Outfit for numbers (assets/fonts, variable wght).
    private val suit by lazy { resources.getFont(R.font.suit) }
    private fun font(weight: Int): Typeface = Typeface.create(suit, weight, false)
    private val numFaces = mutableMapOf<Int, Typeface>()
    private fun num(weight: Int): Typeface = numFaces.getOrPut(weight) {
        Typeface.Builder(assets, "fonts/Outfit.ttf").setFontVariationSettings("'wght' $weight").build() ?: font(700)
    }
    private fun ripple(radius: Float) = RippleDrawable(ColorStateList.valueOf(Color.argb(28, 0, 0, 0)), null, shape(Color.BLACK, null, radius))
    private fun dpf(value: Float) = value * resources.displayMetrics.density
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    // ── labels ──────────────────────────────────────────────────────────────

    private fun tripName(): String {
        val who = draft.companions.firstOrNull()
        return if (who == null || who == "혼자") "혼자 ${draft.destination} 드라이브" else "$who${withParticle(who)} ${draft.destination} 드라이브"
    }
    /** 와/과 by the last syllable's final consonant. */
    private fun withParticle(word: String): String {
        val c = word.lastOrNull() ?: return "와"
        return if (c in '가'..'힣' && (c - '가') % 28 != 0) "과" else "와"
    }
    private fun date(value: String) = runCatching { LocalDate.parse(value) }.getOrNull()
    private fun dateLabel(value: String) = date(value)?.let { "${it.monthValue}월 ${it.dayOfMonth}일" } ?: value
    private fun fullDateLabel(value: String) = date(value)?.let { "${dateLabel(value)} ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}" } ?: value
    private fun dDay(): String {
        val days = date(draft.departure)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) } ?: return "D-?"
        return when { days > 0 -> "D-$days"; days == 0L -> "D-DAY"; else -> "D+${-days}" }
    }
    private fun dDayWords(): String {
        val days = date(draft.departure)?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) } ?: return "날짜를 정해 주세요"
        return when { days > 0 -> "${days}일 남았어요"; days == 0L -> "오늘이에요"; else -> "다녀온 지 ${-days}일이에요" }
    }
    private fun tripLength(): String {
        val start = date(draft.departure); val end = date(draft.endDate)
        if (start == null || end == null || !end.isAfter(start)) return "당일"
        val nights = ChronoUnit.DAYS.between(start, end)
        return "${nights}박 ${nights + 1}일"
    }
    private fun visibilityLabel() = when (draft.visibility) { "나만" -> "나만 보기"; "가족·친구" -> "가족·친구에게"; else -> "전체 공개" }
    private fun placeName(id: String) = (repository.places + repository.radioPlace).find { it.id == id }?.name ?: id
    private fun clock(at: String) = runCatching {
        Instant.parse(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    }.getOrDefault(at)

    private fun chooseDate(end: Boolean) {
        val start = date(draft.departure) ?: LocalDate.now()
        val dialog = DatePickerDialog(this, { _, year, month, day ->
            val value = LocalDate.of(year, month + 1, day)
            if (end && value.isBefore(start)) message("종료일은 출발일 이후로 골라 주세요.")
            else {
                if (end) draft.endDate = value.toString()
                else { draft.departure = value.toString(); if (date(draft.endDate)?.isBefore(value) == true) draft.endDate = "미정" }
                render()
            }
        }, start.year, start.monthValue - 1, start.dayOfMonth)
        if (end) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "미정") { _, _ -> draft.endDate = "미정"; render() }
        dialog.show()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("filter", filter); out.putString("photoTab", photoTab); out.putStringArrayList("selected", ArrayList(selectedPlaces))
        out.putIntegerArrayList("photos", ArrayList(selectedPhotos)); out.putIntegerArrayList("recorded", ArrayList(recordedPhotos))
        out.putStringArrayList("moments", ArrayList(moments)); out.putInt("recap", recapIndex)
        out.putInt("past", pastIndex); out.putInt("summaryPast", summaryPast ?: -1)
        out.putStringArrayList("driveEvents", ArrayList(driveEvents.map { it.toJson() }))
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        filter = state.getString("filter", "전체"); photoTab = state.getString("photoTab", "제안")
        selectedPlaces.clear(); selectedPlaces.addAll(state.getStringArrayList("selected").orEmpty())
        selectedPhotos.addAll(state.getIntegerArrayList("photos").orEmpty()); recordedPhotos.addAll(state.getIntegerArrayList("recorded").orEmpty())
        moments.addAll(state.getStringArrayList("moments").orEmpty()); recapIndex = state.getInt("recap")
        pastIndex = state.getInt("past"); summaryPast = state.getInt("summaryPast", -1).takeIf { it >= 0 }
        driveEvents.addAll(state.getStringArrayList("driveEvents").orEmpty().mapNotNull { runCatching { DriveEvent.fromJson(it) }.getOrNull() })
    }
}

/** Wraps children onto new rows, for the interest chips (the platform has no flow layout). */
private class FlowLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            x += child.measuredWidth + gap; rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), y + rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (x > 0 && x + child.measuredWidth > maxWidth) { x = 0; y += rowHeight + gap; rowHeight = 0 }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap; rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}

private const val REQUEST_LOCATION = 7
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
