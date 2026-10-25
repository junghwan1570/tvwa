// Cloudflare Worker: SBS·MBC·KBS 토큰 API + 영상(m3u8/ts) 중계
//  ?url=<API 주소>     → 토큰 발급 API 응답을 그대로 전달
//  ?stream=<m3u8/ts>  → 영상 재생목록·조각을 CORS 허용해서 전달 (재생목록 안 주소도 Worker 경유로 변환)
//  ?epg=1            → 편성표(스카이라이프 + 삼성TV) 현재~12시간치를 JSON으로
//  ?yt=popular&cat=N → 유튜브 한국 인기 영상 (docker-compose.yml의 YT_API_KEY 사용)
//  잠금: 내 웹 페이지(github.io)와 명왕사신TV 앱에서 온 요청만 받습니다.
//  삼성 TV Plus(jmp2.uk)는 여러 영상 CDN으로 넘어가므로 stream 모드는 모든 https 주소를 허용합니다.
const REFERER = [
  [/(^|\.)sbs\.co\.kr$/, 'https://www.sbs.co.kr/'],
  [/(^|\.)imbc\.com$/, 'https://onair.imbc.com/'],
  [/(^|\.)kbs\.co\.kr$/, 'https://onair.kbs.co.kr/'],
  [/(^|\.)jtbc\.co\.kr$/, 'https://onair.jtbc.co.kr/'],
  [/(^|\.)chzzk\.naver\.com$/, 'https://chzzk.naver.com/'], // 치지직 API
  [/(^|\.)(pstatic|naver)\.net$/, 'https://chzzk.naver.com/'], // 치지직 영상
];

// ───────── 잠금: 이 주소에서 연 페이지의 요청만 받음 ─────────
const ALLOW = [
  'https://junghwan1570.github.io',        // 웹(테슬라·PC·폰 브라우저)
  'https://appassets.androidplatform.net', // 명왕사신TV 앱 안의 화면
];
function allowed(req) {
  const o = req.headers.get('Origin');
  if (o) return ALLOW.includes(o);
  const r = req.headers.get('Referer') || '';
  return ALLOW.some(a => r === a || r.startsWith(a + '/'));
}
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36';
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Expose-Headers': '*' };

function refererFor(host) {
  for (const [re, ref] of REFERER) if (re.test(host)) return ref;
  return null;
}

export default {
  async fetch(req) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    if (!allowed(req)) return new Response('forbidden', { status: 403, headers: CORS });
    const me = new URL(req.url);
    if (me.searchParams.has('epg')) return epg();
    if (me.searchParams.get('yt') === 'popular') return yt(me.searchParams.get('cat') || '0');
    const isStream = me.searchParams.has('stream');
    const target = me.searchParams.get(isStream ? 'stream' : 'url');

    let u;
    try { u = new URL(target); } catch { return new Response('bad url', { status: 400, headers: CORS }); }
    const ref = refererFor(u.hostname);
    if (!ref && (!isStream || u.protocol !== 'https:')) return new Response('host not allowed', { status: 403, headers: CORS });

    const headers = ref
      ? { Referer: ref, Origin: ref.slice(0, -1), 'User-Agent': UA }
      : { 'User-Agent': 'okhttp/4.12.0' }; // 삼성 TV Plus 등
    const range = req.headers.get('Range');
    if (range) headers.Range = range;

    const r = await fetch(u, { headers, cf: { cacheTtl: 0 } });
    const type = r.headers.get('Content-Type') || '';

    // 재생목록이면 안의 주소들을 Worker 경유로 바꿈
    const finalPath = new URL(r.url || u.href).pathname; // jmp2.uk처럼 리다이렉트된 최종 주소 기준
    if (isStream && (/mpegurl/i.test(type) || /\.m3u8$/i.test(u.pathname) || /\.m3u8$/i.test(finalPath))) {
      const base = r.url || u.href;
      const wrap = x => `${me.origin}${me.pathname}?stream=${encodeURIComponent(new URL(x, base).href)}`;
      const body = (await r.text()).split('\n').map(line => {
        const t = line.trim();
        if (!t) return line;
        if (t.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, x) => `URI="${wrap(x)}"`);
        return wrap(t);
      }).join('\n');
      return new Response(body, { status: r.status, headers: { ...CORS, 'Content-Type': 'application/vnd.apple.mpegurl', 'Cache-Control': 'no-store' } });
    }

    const out = new Headers(CORS);
    for (const h of ['Content-Type', 'Content-Length', 'Content-Range', 'Accept-Ranges']) {
      const v = r.headers.get(h); if (v) out.set(h, v);
    }
    out.set('Cache-Control', 'no-store');
    return new Response(r.body, { status: r.status, headers: out });
  },
};

// ───────── 편성표 ─────────
const KST = 9 * 3600e3;
const kstToMs = t => Date.UTC(+t.slice(0, 4), +t.slice(4, 6) - 1, +t.slice(6, 8), +t.slice(8, 10), +t.slice(10, 12), +(t.slice(12, 14) || 0)) - KST;
const ymd = ms => new Date(ms + KST).toISOString().slice(0, 10).replace(/-/g, '');
const HORIZON = 12 * 3600e3, MAXP = 14;

function trim(list, now) {
  return list.filter(p => p[1] > now && p[0] < now + HORIZON).sort((a, b) => a[0] - b[0]).slice(0, MAXP)
    .map(p => [Math.round(p[0] / 1000), Math.round(p[1] / 1000), p[2]]);
}

async function skylife(now) {
  const days = [ymd(now)];
  if (new Date(now + KST).getUTCHours() >= 14) days.push(ymd(now + 86400e3));
  const out = {};
  for (const d of days) {
    const r = await fetch(`https://www.skylife.co.kr/apiBaseUrl/api/public/tv/schedule/${d}`, { headers: { 'User-Agent': UA, Referer: 'https://www.skylife.co.kr/product/tv/channelNo/chart', Accept: 'application/json' } });
    if (!r.ok) throw new Error('skylife ' + r.status);
    for (const cat of await r.json()) for (const ch of cat.channels || []) {
      const a = out[ch.name] || (out[ch.name] = []);
      for (const p of ch.programs || []) if (p.startTime && p.endTime) a.push([kstToMs(p.startTime), kstToMs(p.endTime), p.name]);
    }
  }
  for (const k in out) { out[k] = trim(out[k], now); if (!out[k].length) delete out[k]; }
  if (!Object.keys(out).length) throw new Error('skylife empty');
  return out;
}

async function wavve(now) {
  const f = ms => new Date(ms + KST).toISOString().slice(0, 16).replace('T', ' ');
  const u = `https://apis.pooq.co.kr/live/epgs?startdatetime=${encodeURIComponent(f(now - 3600e3))}&enddatetime=${encodeURIComponent(f(now + HORIZON))}&genre=all&limit=500&offset=0&apikey=E5F3E0D30947AA5440556471321BB6D9`;
  const r = await fetch(u, { headers: { 'User-Agent': UA } });
  if (!r.ok) throw new Error('wavve ' + r.status);
  const j = await r.json(), out = {};
  for (const ch of j.list || []) {
    const ps = (ch.list || ch.epgs || []).map(p => [Date.parse(p.starttime.replace(' ', 'T') + ':00+09:00'), Date.parse(p.endtime.replace(' ', 'T') + ':00+09:00'), p.title]);
    const t = trim(ps, now); if (t.length) out[ch.channelname] = t;
  }
  return out;
}

async function samsung(now) {
  const r = await fetch('https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/SamsungTVPlus/kr.xml');
  if (!r.ok) throw new Error('samsung ' + r.status);
  const x = await r.text(), out = {};
  const re = /<programme channel="([^"]+)" start="(\d{14}) \+0000" stop="(\d{14}) \+0000">\s*<title>([^<]*)<\/title>/g;
  const utc = t => Date.UTC(+t.slice(0, 4), +t.slice(4, 6) - 1, +t.slice(6, 8), +t.slice(8, 10), +t.slice(10, 12), +t.slice(12, 14));
  const un = s => s.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'");
  let m;
  while ((m = re.exec(x))) (out[m[1]] || (out[m[1]] = [])).push([utc(m[2]), utc(m[3]), un(m[4])]);
  for (const k in out) { out[k] = trim(out[k], now); if (!out[k].length) delete out[k]; }
  return out;
}

async function epg() {
  const now = Date.now(), res = { t: Math.round(now / 1000), tv: {}, stvp: {}, src: [], err: [] };
  try { res.tv = await skylife(now); res.src.push('skylife'); }
  catch (e) {
    res.err.push(String(e.message || e));
    try { res.tv = await wavve(now); res.src.push('wavve'); } catch (e2) { res.err.push(String(e2.message || e2)); }
  }
  try { res.stvp = await samsung(now); res.src.push('samsung'); } catch (e) { res.err.push(String(e.message || e)); }
  return new Response(JSON.stringify(res), { headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'public, max-age=600' } });
}

// ───────── 유튜브 인기 영상 ─────────
const ytKey = () => (globalThis.process && process.env && process.env.YT_API_KEY) || '';
function isoDur(d) {
  const m = /P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/.exec(d || '') || [];
  const h = (+m[1] || 0) * 24 + (+m[2] || 0), mi = +m[3] || 0, sec = +m[4] || 0;
  return h ? `${h}:${String(mi).padStart(2, '0')}:${String(sec).padStart(2, '0')}` : `${mi}:${String(sec).padStart(2, '0')}`;
}
async function yt(cat) {
  const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { ...CORS, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' } });
  const key = ytKey();
  if (!key) return json({ error: 'YT_API_KEY 가 없습니다' }, 500);
  const u = new URL('https://www.googleapis.com/youtube/v3/videos');
  u.search = new URLSearchParams({ part: 'snippet,contentDetails,statistics,status', chart: 'mostPopular', regionCode: 'KR', hl: 'ko', maxResults: '50', key });
  if (/^\d+$/.test(cat) && cat !== '0') u.searchParams.set('videoCategoryId', cat);
  try {
    const r = await fetch(u);
    const j = await r.json().catch(() => ({}));
    if (!r.ok) return json({ error: (j.error && j.error.message) || ('HTTP ' + r.status) }, 502);
    const items = (j.items || []).filter(v => v.status && v.status.embeddable !== false && !(v.contentDetails && v.contentDetails.regionRestriction && v.contentDetails.regionRestriction.blocked && v.contentDetails.regionRestriction.blocked.includes('KR'))).map(v => ({
      id: v.id, title: v.snippet.title, channel: v.snippet.channelTitle,
      views: +(v.statistics && v.statistics.viewCount || 0),
      dur: isoDur(v.contentDetails && v.contentDetails.duration),
      live: v.snippet.liveBroadcastContent === 'live',
      thumb: ((v.snippet.thumbnails || {}).medium || (v.snippet.thumbnails || {}).default || {}).url || `https://i.ytimg.com/vi/${v.id}/mqdefault.jpg`,
      at: v.snippet.publishedAt,
    }));
    return json({ items });
  } catch (e) { return json({ error: String(e.message || e) }, 502); }
}
