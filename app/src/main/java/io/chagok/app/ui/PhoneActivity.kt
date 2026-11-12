package io.chagok.app.ui

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import io.chagok.app.R
import io.chagok.app.domain.*
import java.time.LocalDate

/** Phone app: P01–P13. Sending to the car is a demo dialog; the car app is a separate APK (src/car). */
class PhoneActivity : ScreenActivity() {
    override val layout = R.layout.activity_phone
    override val startScreen = Screen.HOME
    private val selectedPlaces = linkedSetOf("anmok", "chodang")
    private val selectedPhotos = linkedSetOf<Int>()
    private val recordedPhotos = linkedSetOf<Int>()
    private var filter = "전체"
    private var photoTab = "제안"
    private var addedStop = false

    override fun render() {
        content = findViewById(R.id.screen_content)
        content.removeAllViews()
        findViewById<TextView>(R.id.screen_title).text = screen.title
        findViewById<TextView>(R.id.preview_badge).text = "${screen.board} · 샘플 데이터 · 연동 전"
        findViewById<Button>(R.id.back_button).apply {
            visibility = if (history.isEmpty()) View.INVISIBLE else View.VISIBLE
            setOnClickListener { onBackPressed() }
        }
        navigation()
        when (screen) {
            Screen.HOME -> {
                map(); card("${draft.companions.firstOrNull() ?: "나"}와 ${draft.destination} 드라이브", "${draft.departure} · 장소 ${itineraryIds.size}곳", "다가오는 여행") { navigate(Screen.ITINERARY) }
                card("저장해 둔 영상에서 다음 여행 찾기", "릴스·쇼츠에서 장소를 모으는 흐름", "발견") { navigate(Screen.RECOMMENDATIONS) }
                text("지난 여행", 13, muted); card("속초 1박 2일", "10월 · 214 km · 장소 7곳 · 예시") { navigate(Screen.SUMMARY) }
            }
            Screen.START -> {
                card("새 여행 만들기", "목적지부터 5단계로") { navigate(Screen.DESTINATION) }
                card("릴스·쇼츠 가져오기", "저장한 영상에서 장소 찾기") { navigate(Screen.IMPORT) }
                card("지금 이 장소 기록", "사진 기록 화면 미리보기 · 실제 위치 미사용") { navigate(Screen.PHOTOS) }
            }
            Screen.DESTINATION -> destination()
            Screen.COMPANIONS -> {
                step(2, "동승자 · 복수 선택")
                listOf("부모님", "아이와 가족", "연인", "친구", "혼자").forEach { label ->
                    choice(label, label in draft.companions) {
                        if (label in draft.companions) draft.companions.remove(label)
                        else { if (label == "혼자") draft.companions.clear() else draft.companions.remove("혼자"); draft.companions.add(label) }; render()
                    }
                }
                button("다음") { if (draft.companions.isEmpty()) message("동승자를 하나 이상 골라 주세요.") else navigate(Screen.INTERESTS) }
            }
            Screen.INTERESTS -> {
                step(3, "관심사 · 최대 3개")
                choices(listOf("지역 이야기", "맛집", "추억", "역사", "자연·바다", "카페", "사진 명소", "전시·문화", "시장", "산책"), { it in draft.interests }) {
                    if (it in draft.interests) draft.interests.remove(it) else if (draft.interests.size < 3) draft.interests.add(it) else message("관심사는 3개까지 고를 수 있어요."); render()
                }
                button("다음") { if (draft.interests.isEmpty()) message("관심사를 하나 이상 골라 주세요.") else navigate(Screen.MOOD) }
            }
            Screen.MOOD -> {
                step(4, "분위기"); choices(listOf("잔잔한", "활기찬", "감성적인"), { it == draft.mood }) { draft.mood = it; render() }
                text("일정 밀도"); choices(listOf("느긋", "보통", "알차게"), { it == draft.density }) { draft.density = it; render() }
                button("다음") { navigate(Screen.IMPORT) }
            }
            Screen.IMPORT -> {
                step(5, "릴스·쇼츠 가져오기"); text("건너뛰어도 일정을 만들 수 있어요.", 14, muted)
                listOf("유튜브 연결", "공유로 보내기", "인스타 내려받기 파일", "유튜브 시청 기록 파일").forEach { title -> card(title, "연동 예정 · 샘플 화면만 제공") { integration(repository.importFrom(title)) } }
                text("인스타 계정 연결 · 미지원", 13, muted)
                button("샘플 장소 추천 보기") { navigate(Screen.RECOMMENDATIONS) }; button("가져오기 건너뛰기", false) { navigate(Screen.ITINERARY) }
            }
            Screen.RECOMMENDATIONS -> {
                text("영상에서 찾은 장소 · 샘플", 15, muted)
                choices(listOf("전체", "저장", "좋아요", "보낸"), { it == filter }) { filter = it; render() }
                repository.places.filter { filter == "전체" || it.group == filter }.forEach { place ->
                    choice("${place.name}\n${place.source}\n${place.reason}", place.id in selectedPlaces) { if (!selectedPlaces.add(place.id)) selectedPlaces.remove(place.id); render() }
                }
                button("${selectedPlaces.size}곳을 일정에 담기") { if (selectedPlaces.isEmpty()) message("장소를 하나 이상 골라 주세요.") else { itineraryIds.clear(); itineraryIds.addAll(selectedPlaces); navigate(Screen.ITINERARY) } }
            }
            Screen.ITINERARY -> itinerary()
            Screen.ARRIVAL -> {
                card("초당 순두부 거리에 도착했어요", "이 장소의 사진 한 장을 남길까요?", "도착 알림 예시")
                button("사진 남기기") { navigate(Screen.PHOTOS) }; button("나중에", false) { navigate(Screen.TIMELINE) }
                text("실제 위치 감지·시스템 알림은 연결 전입니다.", 12, muted)
            }
            Screen.PHOTOS -> photos()
            Screen.TIMELINE -> {
                text("${draft.destination} · ${draft.departure}", 15, muted); map()
                card("안목 커피거리", "10:32 · 사진 3 · 메모 1 · 예시") { navigate(Screen.PHOTOS) }
                card("초당 순두부 거리", "12:34 · 선택한 샘플 사진 ${recordedPhotos.size}장") { navigate(Screen.PHOTOS) }
                if (!addedStop) card("이름 없는 장소", "15:02 · 새로 들른 곳 제안 · 예시", "기록으로 추가") { addedStop = true; render() } else card("새로운 발자취", "샘플 장소를 기록에 추가했어요")
                button("여행 요약 보기") { navigate(Screen.SUMMARY) }; button("도착 알림 흐름 보기", false) { navigate(Screen.ARRIVAL) }
            }
            Screen.SUMMARY -> {
                text("${draft.destination} 당일", 23); card("214 km     3곳     2개", "이동 거리     들른 장소     들은 이야기\n통계는 고정된 예시입니다.")
                card("여행 영상", "사진을 짧은 영상으로") { message("영상 생성은 다음 구현 단계예요.") }
                card("여행 책자", "경로 + 사진 + 메모 · 사진 5장부터") { message(if (recordedPhotos.size < 5) "샘플 사진을 5장 이상 선택해 주세요." else "책자 생성은 다음 구현 단계예요.") }
                text("공개 범위 · ${draft.visibility}\n현재 기기 밖으로 전송·공유하지 않아요.", 13, muted)
                button("공유 · 연동 예정", false) { message("공유 기능은 연결 전입니다.") }
            }
            else -> Unit
        }
        findViewById<ScrollView>(R.id.screen_scroll).scrollTo(0, 0)
    }

    private fun navigation() {
        val nav = findViewById<LinearLayout>(R.id.bottom_navigation); nav.removeAllViews()
        listOf("여행" to Screen.HOME, "추천" to Screen.RECOMMENDATIONS, "+" to Screen.START, "기록" to Screen.TIMELINE, "MY" to Screen.SUMMARY).forEach { (label, route) -> nav.addView(Button(this).apply {
            text = label; textSize = if (label == "+") 24f else 12f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(0, 0, 0, 0)
            setTextColor(if (screen == route || label == "+") ink else muted)
            setBackgroundResource(if (label == "+") R.drawable.bg_choice_selected else R.drawable.bg_nav)
            contentDescription = if (label == "+") "새 여행 또는 기록 시작" else label
            setOnClickListener { navigate(route) }
        }, LinearLayout.LayoutParams(0, dp(56), 1f)) }
    }

    private fun destination() {
        step(1, "목적지·날짜"); text("목적지", 13, muted)
        val input = EditText(this).apply {
            setSingleLine(true); setText(draft.destination); hint = "도시 또는 장소"; setTextColor(ink)
            setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundResource(R.drawable.bg_card)
            addTextChangedListener(SimpleTextWatcher { draft.destination = it })
        }; add(input)
        button("출발 · ${draft.departure}", false) { chooseDate(false) }; button("종료 · ${draft.endDate}", false) { chooseDate(true) }
        add(Switch(this).apply { text = "이동 기록 켜기"; isChecked = draft.recordRoute; setTextColor(ink); minHeight = dp(48); setOnCheckedChangeListener { _, checked -> draft.recordRoute = checked } })
        text("설정만 저장합니다. 실제 위치 수집은 연결 전이에요.", 12, muted)
        text("공개 범위", 13, muted); choices(listOf("나만", "가족·친구", "전체"), { it == draft.visibility }) { draft.visibility = it; render() }
        text("선택은 시연용이며 외부에 게시하지 않습니다.", 12, muted)
        button("다음") { if (draft.destination.isBlank()) input.error = "목적지를 입력해 주세요" else { draft.destination = draft.destination.trim(); navigate(Screen.COMPANIONS) } }
    }

    private fun itinerary() {
        text("${draft.destination} · ${draft.departure}", 15, muted); map()
        itineraryPlaces().forEachIndexed { index, place ->
            card("${index + 1}. ${place.name}", "예시 ${place.time} · ${place.duration}\n길게 누르면 순서 편집") { message("장소 메모 편집은 다음 구현 단계예요.") }.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(place.name).setItems(arrayOf("위로 이동", "아래로 이동")) { _, action ->
                    val destination = if (action == 0) index - 1 else index + 1
                    if (destination in itineraryIds.indices) { val id = itineraryIds.removeAt(index); itineraryIds.add(destination, id); render() }
                }.show(); true
            }
        }
        button("사이에 들를 곳 더 찾기", false) { navigate(Screen.RECOMMENDATIONS) }
        button("차로 보내기 · 데모") { integration(repository.stageTrip(draft, itineraryPlaces())) }
    }

    private fun photos() {
        text("초당 순두부 거리", 18)
        choices(listOf("제안", "내 앨범", "촬영"), { it == photoTab }) { photoTab = it; render() }
        if (photoTab != "제안") { text("${photoTab} 연동 전입니다. 기기의 사진·카메라에 접근하지 않아요.", 14, muted); return }
        text("샘플 조건 · 200m 안 · 12:20–13:40\n실제 사진·EXIF를 읽지 않습니다.", 12, muted)
        choices((1..6).map { "예시 사진 $it" }, { it.last().digitToInt() in selectedPhotos }) {
            val number = it.last().digitToInt(); if (!selectedPhotos.add(number)) selectedPhotos.remove(number); render()
        }
        button("${selectedPhotos.size}장 담기 · 예시") { if (selectedPhotos.isEmpty()) message("샘플 사진을 하나 이상 골라 주세요.") else { recordedPhotos.addAll(selectedPhotos); navigate(Screen.TIMELINE) } }
    }

    private fun step(n: Int, label: String) { text("$n / 5  ·  $label", 13, muted) }
    private fun map() { layoutInflater.inflate(R.layout.panel_map, content, true) }
    private fun chooseDate(end: Boolean) {
        val start = runCatching { LocalDate.parse(draft.departure) }.getOrDefault(LocalDate.now())
        val dialog = DatePickerDialog(this, { _, year, month, day ->
            val value = LocalDate.of(year, month + 1, day)
            if (end && value.isBefore(start)) message("종료일은 출발일 이후로 골라 주세요.")
            else { if (end) draft.endDate = value.toString() else { draft.departure = value.toString(); if (runCatching { LocalDate.parse(draft.endDate).isBefore(value) }.getOrDefault(false)) draft.endDate = "미정" }; render() }
        }, start.year, start.monthValue - 1, start.dayOfMonth)
        if (end) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "미정") { _, _ -> draft.endDate = "미정"; render() }
        dialog.show()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("filter", filter); out.putString("photoTab", photoTab); out.putStringArrayList("selected", ArrayList(selectedPlaces))
        out.putIntegerArrayList("photos", ArrayList(selectedPhotos)); out.putIntegerArrayList("recorded", ArrayList(recordedPhotos)); out.putBoolean("added", addedStop)
        super.onSaveInstanceState(out)
    }
    override fun restore(state: Bundle) {
        super.restore(state)
        filter = state.getString("filter", "전체"); photoTab = state.getString("photoTab", "제안")
        selectedPlaces.clear(); selectedPlaces.addAll(state.getStringArrayList("selected").orEmpty())
        selectedPhotos.addAll(state.getIntegerArrayList("photos").orEmpty()); recordedPhotos.addAll(state.getIntegerArrayList("recorded").orEmpty())
        addedStop = state.getBoolean("added")
    }
}
