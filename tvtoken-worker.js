// Cloudflare Worker: SBS·MBC·KBS 토큰 API + 영상(m3u8/ts) 중계
//  ?url=<API 주소>     → 토큰 발급 API 응답을 그대로 전달
//  ?stream=<m3u8/ts>  → 영상 재생목록·조각을 CORS 허용해서 전달 (재생목록 안 주소도 Worker 경유로 변환)
//  삼성 TV Plus(jmp2.uk)는 여러 영상 CDN으로 넘어가므로 stream 모드는 모든 https 주소를 허용합니다.
const REFERER = [
  [/(^|\.)sbs\.co\.kr$/, 'https://www.sbs.co.kr/'],
  [/(^|\.)imbc\.com$/, 'https://onair.imbc.com/'],
  [/(^|\.)kbs\.co\.kr$/, 'https://onair.kbs.co.kr/'],
];
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36';
const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Expose-Headers': '*' };

function refererFor(host) {
  for (const [re, ref] of REFERER) if (re.test(host)) return ref;
  return null;
}

export default {
  async fetch(req) {
    if (req.method === 'OPTIONS') return new Response(null, { headers: CORS });
    const me = new URL(req.url);
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
