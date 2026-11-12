const DEFAULT_SCREEN = __SCREEN__;
const $ = id => document.getElementById(id);
let screen = DEFAULT_SCREEN, playing = false, toastTimer, detail = null, discoveryIndex = 0;
let selected = { id: 'gyeongpodae', name: '경포대', extra: 12 }, saved = [];
let plan = { destination: '강릉', date: '11월 22일', companions: '부모님', mood: '잔잔한', places: ['안목 커피거리', '초당 순두부 거리', '경포 호수 산책길'] };
const places = [
  { id: 'anmok', name: '안목 커피거리', distance: '3.2 km · 8분', source: '저장한 릴스 2개', symbol: '☕', extra: 8 },
  { id: 'chodang', name: '초당 순두부 거리', distance: '5.1 km · 12분', source: '좋아요한 쇼츠 1개', symbol: '♧', extra: 12 },
  { id: 'gyeongpo', name: '경포 호수 산책길', distance: '8.4 km · 18분', source: '근처에서 발견한 산책길', symbol: '⌁', extra: 18 },
];
const esc = x => String(x).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function send(message) { if (parent !== window) parent.postMessage(message, location.origin); }
function go(value, notify = true) {
  screen = value; detail = null; playing = false; render();
  if (notify) send({ type: 'chagok-screen', group: 'car', screen });
}
function toast(text) {
  clearTimeout(toastTimer); $('toast').textContent = text; $('toast').hidden = false;
  toastTimer = setTimeout(() => $('toast').hidden = true, 3000);
}
function orb() {
  return `<div class="orb-stage"><button class="orb-button" data-action="play" aria-label="${playing ? '말하기 애니메이션 멈추기' : '말하기 애니메이션 보기'}" aria-pressed="${playing}"><span class="orb-glow"></span><span class="orb-core"><span class="orb-color"></span><span class="orb-haze"></span></span></button></div>`;
}
function detailContent() {
  let title = '', body = '';
  if (detail === 'schedule') {
    title = '오늘의 작은 여행';
    body = `<p class="sub">${esc(plan.date)} · ${esc(plan.destination)} · ${esc(plan.companions)} · ${esc(plan.mood)} 분위기</p><div class="schedule">${plan.places.map((name, i) => `<div class="stop"><time>${['10:30', '12:30', '15:00', '16:30'][i]}</time><span class="stop-number">${i + 1}</span><strong>${esc(name)}</strong></div>`).join('')}</div><button class="primary" data-car="15">차곡과 출발하기　→</button>`;
  } else if (detail === 'discover') {
    title = '길 위에서 발견한 곳';
    body = `<p class="sub">마음에 드는 곳을 담아 두세요.</p><div class="discover-list">${places.map(p => `<article class="place-card"><span class="place-symbol">${p.symbol}</span><div><strong>${p.name}</strong><p>${p.distance}</p><small>${p.source}</small></div><button data-place="${p.id}">담기 ＋</button></article>`).join('')}</div>`;
  } else {
    title = '차곡차곡 쌓인 오늘';
    body = `<div class="stats"><div><b>214<span style="font:12px sans-serif"> km</span></b><small>함께 달린 거리</small></div><div><b>3</b><small>들른 장소</small></div><div><b>2</b><small>들은 이야기</small></div></div><div class="memory-note"><small>다음에 다시 만나고 싶은 곳</small>${saved.length ? saved.map(p => esc(p.name)).join(' · ') : '아직 저장한 장소가 없어요.'}</div><button class="primary" data-action="phone-record">폰에서 오늘의 기록 이어가기　→</button>`;
  }
  return `<section class="detail-layer" role="dialog" aria-modal="false" aria-label="${title}"><div class="detail-head"><strong>${title}</strong><button data-action="close-detail" aria-label="차곡으로 돌아가기">×</button></div>${body}</section>`;
}
function render() {
  $('drive-state').textContent = screen === 15 || screen === 16 ? '주행 화면 예시' : '정차 중';
  $('tabs').innerHTML = '<span class="assistant-caption">' + (screen === 18 ? '오늘의 기록' : esc(plan.destination)) + '</span>';
  let line = '', context = '', actions = '', status = '', footer = '';
  if (screen === 14) {
    status = '오늘의 여정을 준비했어요';
    line = `${esc(plan.destination)}에서 만날 ${plan.places.length}곳의 이야기,<br>함께 떠나 볼까요?`;
    context = `${esc(plan.companions)} · ${esc(plan.mood)} 분위기로 준비했어요.`;
    actions = '<button data-detail="schedule">어디로 가는지 알려줘</button><button class="main" data-car="15">좋아, 출발하자　↗</button>';
  } else if (screen === 15) {
    status = '지금, 차창 밖에서 발견한 이야기';
    line = '저 바다를 보면 떠오르는<br>여행이 있으세요?';
    context = '옆으로 지나가는 경포대, 잠깐의 이야기를 건네 볼게요.';
    actions = `<button data-action="play">${playing ? '잠깐 쉬어 가자' : '말하는 모습 보기'}</button><button class="main" data-action="save">이 장소 기억해 줘　♡</button>`;
    footer = `<span>다음 · ${esc(plan.places[0] || '안목 커피거리')}　18분</span><span class="divider"></span><button data-detail="schedule">일정</button><button data-car="17">발견</button><button data-action="arrive">도착 화면 →</button>`;
  } else if (screen === 16) {
    status = '마음에 담아 두었어요';
    line = `${esc(selected.name)}에<br>잠깐 들렀다 갈까요?`;
    context = `경유지로 추가하면 ${selected.extra}분 더 걸려요. · 예시`;
    actions = '<button data-car="15">다음에 가자</button><button class="main" data-action="approve">응, 들렀다 가자　→</button>';
  } else if (screen === 17) {
    const place = places[discoveryIndex];
    status = '이런 곳은 어떠세요?';
    line = `${esc(place.name)}에서<br>조금 쉬어 가도 좋겠어요.`;
    context = `${place.source} · ${place.distance}`;
    actions = '<button data-action="another-place">다른 곳도 알려줘</button><button class="main" data-action="save-suggestion">여기 마음에 들어　♡</button>';
  } else {
    status = '오늘도 함께해 줘서 고마워요';
    line = '오늘, 가장 오래 기억하고 싶은<br>순간은 무엇인가요?';
    context = '함께 달린 214 km · 들른 곳 3곳 · 예시 기록';
    actions = '<button data-detail="record">오늘을 돌아볼래</button><button class="main" data-action="phone-record">사진으로 남겨 볼래　↗</button>';
  }
  if (!footer) footer = '<button data-detail="schedule">오늘 일정</button><span class="divider"></span><button data-car="17">주변 발견</button><span class="divider"></span><button data-detail="record">여행 기록</button>';
  $('car-content').innerHTML = `<div class="assistant ${playing ? 'is-speaking' : ''} ${screen === 16 ? 'approving' : ''}">${orb()}<div class="orb-status"><span class="speech-dots" aria-hidden="true"><i></i><i></i><i></i></span><span>${playing ? '차곡이 이야기하는 모습 · 음성 연결 전' : status}</span></div><h1 class="assistant-line">${line}</h1><p class="assistant-context">${context}</p><div class="suggestions">${actions}</div><div class="assistant-footer">${footer}</div>${detail ? detailContent() : ''}</div>`;
}
function save(place) {
  selected = place;
  if (!saved.some(p => p.id === place.id)) saved.push(place);
  send({ type: 'chagok-place-saved', place: { id: place.id, name: place.name } });
  go(16);
}
document.addEventListener('click', event => {
  const button = event.target.closest('button'); if (!button) return;
  if (button.dataset.car) { go(Number(button.dataset.car)); return; }
  if (button.dataset.detail) { detail = button.dataset.detail; playing = false; render(); return; }
  if (button.dataset.place) { save(places.find(p => p.id === button.dataset.place)); return; }
  switch (button.dataset.action) {
    case 'play': playing = !playing; render(); break;
    case 'close-detail': detail = null; render(); break;
    case 'save': save({ id: 'gyeongpodae', name: '경포대', extra: 12 }); break;
    case 'save-suggestion': save(places[discoveryIndex]); break;
    case 'another-place': discoveryIndex = (discoveryIndex + 1) % places.length; render(); break;
    case 'approve': go(15); toast('경유지 승인 화면 예시예요. 실제 내비 경로는 바뀌지 않아요.'); break;
    case 'arrive': go(18); break;
    case 'phone-record': detail = null; render(); send({ type: 'chagok-screen', group: 'phone', screen: 10 }); toast('폰 화면에서 오늘의 기억을 이어 보세요.'); break;
  }
});
document.addEventListener('keydown', event => { if (event.key === 'Escape' && detail) { detail = null; render(); } });
window.addEventListener('message', event => {
  if (event.source !== parent || event.origin !== location.origin) return;
  const data = event.data;
  if (data?.type === 'chagok-car-screen' && [14, 15, 16, 17, 18].includes(data.screen)) go(data.screen, false);
  if (data?.type === 'chagok-plan' && data.plan) {
    const p = data.plan;
    plan = { destination: String(p.destination || '강릉').slice(0, 40), date: String(p.date || '11월 22일').slice(0, 30), companions: String(p.companions || '부모님').slice(0, 60), mood: String(p.mood || '잔잔한').slice(0, 20), places: Array.isArray(p.places) ? p.places.slice(0, 4).map(x => String(x).slice(0, 50)) : plan.places };
    go(14, false);
  }
});
render(); send({ type: 'chagok-ready', group: 'car' });
