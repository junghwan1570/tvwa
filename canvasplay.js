/**
 * canvasplay.js — 명왕사신TV 캔버스 재생 (주행 중에도 재생, 원본 화질)
 *   HLS(.m3u8 → TS 조각) 또는 WebSocket TS → WebCodecs → <canvas> + WebAudio
 *   new H264Player(null, { feed: true, ... }) + new HlsFeeder(url, player, onFail)
 *
 * (아래는 canvas-stream의 h264player.js와 같은 플레이어)
 * h264player.js — 원본 H.264/AAC MPEG-TS(WebSocket) → WebCodecs → <canvas> + WebAudio
 *
 * 서버가 "원본" 화질일 때 영상을 다시 인코딩하지 않고 보내므로, 여기서
 *   TS 분리 → VideoDecoder(H.264) → canvas,  AudioDecoder(AAC) → AudioContext
 * 로 재생합니다. JSMpeg.Player와 같은 모양의 API를 내놓아 index.html이
 * 플레이어만 바꿔 끼우면 되게 했습니다.
 *
 *   new H264Player(wsUrl, { canvas, onVideoDecode, onSocketClose, audio, avOffset, bufferMs })
 *   .destroy()  .volume = 0..2  .audioOut.{unlocked, unlock(cb), volume, gain, context}
 *   .setAvOffset(sec)   > 0 = 소리가 늦음 → 영상을 그만큼 늦게 보여 줌
 */
(function () {
  'use strict';

  const TS = 188;
  const hex2 = (b) => b.toString(16).padStart(2, '0').toUpperCase();

  function H264Player(url, o) {
    this.o = o || {};
    this.canvas = this.o.canvas;
    this.ctx2d = this.canvas.getContext('2d', { alpha: false, desynchronized: true });
    this.source = this;                       // index.html이 "내 소켓인가" 확인할 때 씀
    this.avOffset = Number(this.o.avOffset) || 0;
    this.bufferSec = Math.max(0.2, (Number(this.o.bufferMs) || 600) / 1000);
    this.wantAudio = this.o.audio !== false;
    this.dead = false;

    // demux 상태
    this.pmtPid = -1; this.vPid = -1; this.aPid = -1; this.vType = 0; this.aType = 0;
    this.pes = new Map();                     // pid → { chunks: [], len }

    // 디코더
    this.vdec = null; this.vCodec = ''; this.waitKey = true; this.vErr = 0;
    this.adec = null; this.aCfg = '';
    this.frames = [];                         // 풀린 VideoFrame (pts 순)
    this.encV = []; this.encA = [];           // 아직 안 푼 조각: 보여 줄 때가 가까워지면 풂 (메모리 절약)
    this.latestPts = null;                    // 받은 영상 중 가장 늦은 pts(초)

    // 시계: mediaNow = anchorPts + (지금 - anchorWall)
    this.state = 'buffering'; this.anchorPts = 0; this.anchorWall = 0; this.frozenAt = null;

    // 소리
    this._volume = 1;
    this.actx = null; this.gain = null; this.nextAudioAt = 0;
    this.sharedCtx = !!this.o.audioContext;
    if (this.wantAudio) {
      const AC = window.AudioContext || window.webkitAudioContext;
      if (this.o.audioContext || AC) {
        this.actx = this.o.audioContext || new AC({ latencyHint: 'playback' });
        this.gain = this.actx.createGain();
        this.gain.connect(this.actx.destination);
      }
    }
    const self = this;
    this.audioOut = {
      get unlocked() { return !self.actx || self.actx.state === 'running'; },
      unlock(cb) { if (!self.actx) return cb && cb(); self.actx.resume().then(() => cb && cb()).catch(() => {}); },
      get volume() { return self._volume; },
      set volume(v) { self.volume = v; },
      get gain() { return self.gain; },
      get context() { return self.actx; },
    };

    this.raf = 0;
    this.loop = this.loop.bind(this);
    this.raf = requestAnimationFrame(this.loop);
    // rAF가 멈추는 환경(백그라운드 등) 대비
    this.tickTimer = setInterval(() => this.render(), 100);

    this.socket = null;
    if (!this.o.feed) {
      this.socket = new WebSocket(url);
      this.socket.binaryType = 'arraybuffer';
      this.socket.onmessage = (ev) => { if (!this.dead) this.push(new Uint8Array(ev.data)); };
      this.socket.onclose = (ev) => { if (!this.dead && this.o.onSocketClose) this.o.onSocketClose(ev, this); };
    }
  }

  /** 일시정지/다시 재생 (다시 재생하면 실시간 위치로 따라붙음) */
  H264Player.prototype.setPaused = function (p) {
    if (p && this.state !== 'paused') {
      this.frozenAt = this.mediaNow(); this.state = 'paused';
      if (this.actx && !this.sharedCtx) this.actx.suspend().catch(() => {});
      if (this.gain) this.gain.gain.value = 0;
    } else if (!p && this.state === 'paused') {
      this.state = 'buffering';
      if (this.gain) this.volume = this._volume;
      if (this.actx) this.actx.resume().catch(() => {});
    }
  };
  H264Player.prototype.isPaused = function () { return this.state === 'paused'; };

  H264Player.supported = function () {
    return typeof window.VideoDecoder === 'function' && typeof window.EncodedVideoChunk === 'function';
  };

  Object.defineProperty(H264Player.prototype, 'volume', {
    get() { return this._volume; },
    set(v) {
      this._volume = Math.max(0, Math.min(2, Number(v) || 0));
      if (this.gain) { try { this.gain.gain.setValueAtTime(this._volume, this.actx.currentTime); } catch (e) { this.gain.gain.value = this._volume; } }
    },
  });

  H264Player.prototype.setAvOffset = function (sec) { this.avOffset = Number(sec) || 0; };

  /* ───────────────────────────── MPEG-TS 분리 ───────────────────────────── */
  H264Player.prototype.push = function (u8) {
    for (let off = 0; off + TS <= u8.length; off += TS) {
      if (u8[off] !== 0x47) continue;
      const pid = ((u8[off + 1] & 0x1f) << 8) | u8[off + 2];
      const pusi = (u8[off + 1] & 0x40) !== 0;
      const afc = (u8[off + 3] >> 4) & 3;
      if (!(afc & 1)) continue;
      let p = off + 4;
      if (afc & 2) p += 1 + u8[off + 4];
      if (p >= off + TS) continue;
      const payload = u8.subarray(p, off + TS);

      if (pid === 0) { if (pusi) this.parsePat(payload); continue; }
      if (pid === this.pmtPid) { if (pusi) this.parsePmt(payload); continue; }
      if (pid !== this.vPid && pid !== this.aPid) continue;

      let pes = this.pes.get(pid);
      if (pusi) {
        if (pes && pes.len) this.flushPes(pid, pes);
        pes = { chunks: [], len: 0 };
        this.pes.set(pid, pes);
      }
      if (!pes) continue;                     // PES 중간부터 받은 경우: 다음 시작까지 버림
      pes.chunks.push(payload.slice());
      pes.len += payload.length;
    }
  };

  H264Player.prototype.parsePat = function (b) {
    const s = 1 + b[0];                       // pointer_field
    const secLen = ((b[s + 1] & 0x0f) << 8) | b[s + 2];
    for (let i = s + 8; i + 4 <= s + 3 + secLen - 4; i += 4) {
      const prog = (b[i] << 8) | b[i + 1];
      if (prog !== 0) { this.pmtPid = ((b[i + 2] & 0x1f) << 8) | b[i + 3]; return; }
    }
  };

  H264Player.prototype.parsePmt = function (b) {
    const s = 1 + b[0];
    const secLen = ((b[s + 1] & 0x0f) << 8) | b[s + 2];
    const end = s + 3 + secLen - 4;
    const pil = ((b[s + 10] & 0x0f) << 8) | b[s + 11];
    for (let i = s + 12 + pil; i + 5 <= end;) {
      const type = b[i], pid = ((b[i + 1] & 0x1f) << 8) | b[i + 2];
      const esl = ((b[i + 3] & 0x0f) << 8) | b[i + 4];
      if ((type === 0x1b || type === 0x24) && this.vPid < 0) { this.vPid = pid; this.vType = type; }
      if ((type === 0x0f || type === 0x11) && this.aPid < 0) { this.aPid = pid; this.aType = type; }
      i += 5 + esl;
    }
    if (this.vType === 0x24 && !this.hevcWarned) {
      this.hevcWarned = true;
      console.warn('[h264] 원본이 H.265입니다. 이 플레이어는 H.264만 지원합니다.');
    }
  };

  H264Player.prototype.flushPes = function (pid, pes) {
    const d = pes.chunks.length === 1 ? pes.chunks[0] : concat(pes.chunks, pes.len);
    if (d.length < 9 || d[0] !== 0 || d[1] !== 0 || d[2] !== 1) return;
    const flags = d[7], hlen = d[8];
    let pts = null;
    if (flags & 0x80) {
      pts = ((d[9] & 0x0e) * 536870912 + d[10] * 4194304 + (d[11] & 0xfe) * 16384 + d[12] * 128 + (d[13] >> 1)) / 90000;
    }
    const es = d.subarray(9 + hlen);
    if (pid === this.vPid) this.onVideo(es, pts);
    else this.onAudio(es, pts);
  };

  function concat(list, len) {
    const out = new Uint8Array(len);
    let o = 0;
    for (const c of list) { out.set(c, o); o += c.length; }
    return out;
  }

  /* ─────────────────────────────── 영상 ─────────────────────────────── */
  function nalTypes(es) {
    // Annex B에서 NAL 종류와 SPS 위치 찾기
    const types = []; let sps = null;
    for (let i = 0; i + 3 < es.length; i++) {
      if (es[i] === 0 && es[i + 1] === 0 && (es[i + 2] === 1 || (es[i + 2] === 0 && es[i + 3] === 1))) {
        const st = es[i + 2] === 1 ? i + 3 : i + 4;
        const t = es[st] & 31;
        types.push(t);
        if (t === 7 && !sps) sps = es.subarray(st, st + 4);
        i = st;
      }
    }
    return { types, sps };
  }

  H264Player.prototype.onVideo = function (es, pts) {
    if (this.vType !== 0x1b || pts === null) return;
    const { types, sps } = nalTypes(es);
    const key = types.includes(5);
    if (sps && sps.length >= 4) {
      const codec = 'avc1.' + hex2(sps[1]) + hex2(sps[2]) + hex2(sps[3]);
      if (codec !== this.vCodec || !this.vdec || this.vdec.state === 'closed') this.makeVideoDecoder(codec);
    }
    if (!this.vdec || this.vdec.state !== 'configured') return;
    if (this.waitKey) { if (!key) return; this.waitKey = false; }
    this.noteMedia(pts);
    this.encV.push({ t: pts, key, data: es.slice() });
    if (this.encV.length > 1800) this.encV.splice(0, 900);   // 비정상적으로 쌓이면 정리
  };

  H264Player.prototype.makeVideoDecoder = function (codec) {
    if (this.vdec) { try { this.vdec.close(); } catch (e) { /* closed */ } }
    this.vCodec = codec; this.waitKey = true; this.needKey = true;
    this.vdec = new VideoDecoder({
      output: (f) => {
        if (this.dead) return f.close();
        const t = f.timestamp / 1e6;
        // 순서대로 넣기 (B프레임 없이 보내지만 혹시 모를 역순 대비)
        let i = this.frames.length;
        while (i > 0 && this.frames[i - 1].t > t) i--;
        this.frames.splice(i, 0, { t, f });
        if (this.frames.length > 90) { const x = this.frames.shift(); x.f.close(); }
      },
      error: (e) => {
        console.warn('[h264] video decoder error:', e && e.message);
        this.vErr += 1;
        if (!this.dead) setTimeout(() => this.makeVideoDecoder(this.vCodec), 50);
      },
    });
    try {
      this.vdec.configure({ codec, optimizeForLatency: true, hardwareAcceleration: 'no-preference' });
    } catch (e) {
      console.warn('[h264] configure failed:', codec, e && e.message);
    }
  };

  /* ─────────────────────────────── 소리 ─────────────────────────────── */
  H264Player.prototype.onAudio = function (es, pts) {
    if (!this.actx || this.aType !== 0x0f || pts === null || typeof window.AudioDecoder !== 'function') return;
    let i = 0, n = 0;
    while (i + 7 <= es.length) {
      if (es[i] !== 0xff || (es[i + 1] & 0xf0) !== 0xf0) { i++; continue; }
      const len = ((es[i + 3] & 3) << 11) | (es[i + 4] << 3) | (es[i + 5] >> 5);
      if (len < 7 || i + len > es.length) break;
      const prof = ((es[i + 2] >> 6) & 3) + 1;
      const sfi = (es[i + 2] >> 2) & 15;
      const ch = ((es[i + 2] & 1) << 2) | (es[i + 3] >> 6);
      const sr = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350][sfi] || 48000;
      const cfg = `mp4a.40.${prof}|${sr}|${ch || 2}`;
      if (cfg !== this.aCfg) this.makeAudioDecoder(cfg, prof, sr, ch || 2);
      this.encA.push({ t: pts + n * 1024 / sr, data: es.slice(i, i + len) });
      if (this.encA.length > 3000) this.encA.splice(0, 1500);
      i += len; n++;
    }
  };

  H264Player.prototype.makeAudioDecoder = function (cfg, prof, sr, ch) {
    if (this.adec) { try { this.adec.close(); } catch (e) { /* closed */ } }
    this.aCfg = cfg;
    this.adec = new AudioDecoder({
      output: (d) => this.playAudio(d),
      error: (e) => { console.warn('[h264] audio decoder error:', e && e.message); this.aCfg = ''; },
    });
    try { this.adec.configure({ codec: 'mp4a.40.' + prof, sampleRate: sr, numberOfChannels: ch }); }
    catch (e) { console.warn('[h264] audio configure failed:', e && e.message); }
  };

  H264Player.prototype.playAudio = function (d) {
    try {
      const now = this.mediaNow();
      if (this.dead || this.state !== 'playing' || now === null || this.actx.state !== 'running') return;
      const t = d.timestamp / 1e6;
      const dur = d.numberOfFrames / d.sampleRate;
      // 소리를 낼 AudioContext 시각: 지금 + (이 조각 pts - 지금 재생 위치)
      let at = this.actx.currentTime + (t - now) + 0.05;
      if (Math.abs(at - this.nextAudioAt) < 0.04) at = this.nextAudioAt;   // 조각 사이 틈·겹침 없애기
      if (at + dur < this.actx.currentTime || at - this.actx.currentTime > 3) return;  // 너무 늦거나 이상하게 이름
      const buf = this.actx.createBuffer(d.numberOfChannels, d.numberOfFrames, d.sampleRate);
      for (let c = 0; c < d.numberOfChannels; c++) {
        const plane = new Float32Array(d.numberOfFrames);
        d.copyTo(plane, { planeIndex: c, format: 'f32-planar' });
        buf.copyToChannel(plane, c);
      }
      const src = this.actx.createBufferSource();
      src.buffer = buf;
      src.connect(this.gain);
      src.start(Math.max(at, this.actx.currentTime));
      this.nextAudioAt = Math.max(at, this.actx.currentTime) + dur;
    } finally {
      d.close();
    }
  };

  /* ─────────────────────────── 시계와 그리기 ─────────────────────────── */
  H264Player.prototype.noteMedia = function (pts) {
    // 큰 시간 점프(원본 끊김·이어 붙임) → 새 시간선으로 다시 맞춤
    if (this.latestPts !== null && (pts < this.latestPts - 1 || pts > this.latestPts + 5)) {
      this.frames.forEach((x) => x.f.close()); this.frames = [];
      this.encV = this.encV.filter((x) => x.t >= pts - 0.01); this.encA = this.encA.filter((x) => x.t >= pts - 1);
      this.state = 'buffering'; this.frozenAt = null; this.latestPts = pts; this.firstPts = pts;
      return;
    }
    if (this.latestPts === null || pts > this.latestPts) this.latestPts = pts;
    if (this.firstPts === undefined || this.firstPts === null) this.firstPts = pts;
  };

  /** 보여 줄 시각이 가까운 조각만 디코더에 넣음 (영상 0.7초, 소리 1초 앞까지) */
  H264Player.prototype.pump = function (now) {
    const vd = this.vdec;
    if (vd && vd.state === 'configured') {
      // 이미 지나간 조각은 다음 키프레임까지 건너뜀(멈췄다 따라붙을 때)
      if (this.encV.length && this.encV[0].t < now - 1) {
        const k = this.encV.findIndex((x) => x.key && x.t >= now - 0.2);
        if (k > 0) this.encV.splice(0, k);
        else if (k < 0) this.needKey = true;
      }
      // 새 디코더·건너뛴 뒤에는 키프레임부터 넣어야 함
      if (this.needKey) {
        const k = this.encV.findIndex((x) => x.key);
        if (k < 0) { this.encV = this.encV.filter((x) => x.t > now + 0.7); }
        else { this.encV.splice(0, k); this.needKey = false; }
      }
      while (!this.needKey && this.encV.length && this.encV[0].t <= now + 0.7 && vd.decodeQueueSize < 12) {
        const c = this.encV.shift();
        try { vd.decode(new EncodedVideoChunk({ type: c.key ? 'key' : 'delta', timestamp: Math.round(c.t * 1e6), data: c.data })); }
        catch (e) { const k = this.encV.findIndex((x) => x.key); this.encV.splice(0, k < 0 ? this.encV.length : k); }
      }
    }
    const ad = this.adec;
    while (this.encA.length && this.encA[0].t < now - 0.3) this.encA.shift();
    if (ad && ad.state === 'configured') {
      while (this.encA.length && this.encA[0].t <= now + 1 && ad.decodeQueueSize < 40) {
        const c = this.encA.shift();
        try { ad.decode(new EncodedAudioChunk({ type: 'key', timestamp: Math.round(c.t * 1e6), data: c.data })); } catch (e) { /* 다음 */ }
      }
    }
  };

  H264Player.prototype.mediaNow = function () {
    if (this.state !== 'playing') return this.frozenAt;
    return this.anchorPts + (performance.now() - this.anchorWall) / 1000;
  };

  H264Player.prototype.loop = function () {
    if (this.dead) return;
    this.render();
    this.raf = requestAnimationFrame(this.loop);
  };

  H264Player.prototype.render = function () {
    if (this.dead || this.latestPts === null || this.state === 'paused') return;
    const wall = performance.now();
    if (this.state === 'buffering') {
      let start = this.frozenAt !== null ? this.frozenAt : (this.encV.length ? this.encV[0].t : this.firstPts);
      if (start === null || start === undefined || this.latestPts - start < this.bufferSec) return;
      // 처음 시작할 때는 쌓인 것 중 실시간에 가까운 쪽(여유분만 남기고)에서 시작
      if (this.frozenAt === null) start = Math.max(start, this.latestPts - this.bufferSec);
      this.state = 'playing'; this.anchorPts = start; this.anchorWall = wall;
    }
    const now = this.anchorPts + (wall - this.anchorWall) / 1000;
    this.pump(now);
    // 받은 것보다 앞서 나가면(네트워크 끊김) 그 자리에서 멈추고 다시 쌓일 때까지 기다림
    if (now > this.latestPts + 0.3) { this.state = 'buffering'; this.frozenAt = this.latestPts; return; }
    // 너무 뒤처지면(쌓인 게 많음) 앞으로 건너뜀
    if (this.latestPts - now > (this.maxLagSec || this.bufferSec + 4)) { this.anchorPts = this.latestPts - this.bufferSec; this.anchorWall = wall; return; }

    // 소리는 스피커까지 늦게 나오므로 그만큼 영상도 늦춰 맞춤
    const lat = this.actx ? 0.05 + (this.actx.baseLatency || 0) + (this.actx.outputLatency || 0) : 0;
    const show = now - this.avOffset - lat;    // avOffset > 0: 영상을 늦게
    let pick = null;
    while (this.frames.length && this.frames[0].t <= show) {
      if (pick) pick.f.close();
      pick = this.frames.shift();
    }
    if (!pick) return;
    const f = pick.f;
    const w = f.displayWidth, h = f.displayHeight;
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h; }
    try { this.ctx2d.drawImage(f, 0, 0, w, h); } catch (e) { /* 프레임 손상 */ }
    f.close();
    if (this.o.onVideoDecode) this.o.onVideoDecode(this, now);
  };

  H264Player.prototype.destroy = function () {
    if (this.dead) return;
    this.dead = true;
    cancelAnimationFrame(this.raf);
    clearInterval(this.tickTimer);
    if (this.socket) { try { this.socket.onmessage = null; this.socket.onclose = null; this.socket.close(); } catch (e) { /* closed */ } }
    this.frames.forEach((x) => { try { x.f.close(); } catch (e) { /* closed */ } });
    this.frames = [];
    for (const d of [this.vdec, this.adec]) { if (d) { try { d.close(); } catch (e) { /* closed */ } } }
    if (this.gain) { try { this.gain.disconnect(); } catch (e) { /* gone */ } }
    if (this.actx && !this.sharedCtx) { try { this.actx.close(); } catch (e) { /* closed */ } }
    if (this.onDestroy) this.onDestroy();
  };

  window.H264Player = H264Player;

  /* ───────────────────── HLS 조각을 받아 플레이어에 넣기 ───────────────────── */
  function HlsFeeder(url, player, onFail) {
    this.player = player; this.onFail = onFail; this.stopped = false;
    this.seen = new Set(); this.queue = []; this.errors = 0; this.height = 0;
    player.onDestroy = () => this.stop();
    this.run(url).catch((e) => this.fail(e.message || String(e)));
  }
  HlsFeeder.prototype.stop = function () { this.stopped = true; if (this.ac) try { this.ac.abort(); } catch (e) { /* */ } };
  HlsFeeder.prototype.fail = function (why) { if (this.stopped) return; this.stop(); if (this.onFail) this.onFail(why); };
  HlsFeeder.prototype.get = async function (u, bin) {
    this.ac = new AbortController();
    const t = setTimeout(() => this.ac.abort(), 15000);
    try {
      const r = await fetch(u, { signal: this.ac.signal, cache: 'no-store' });
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return { body: bin ? new Uint8Array(await r.arrayBuffer()) : await r.text(), url: r.url || u };
    } finally { clearTimeout(t); }
  };
  HlsFeeder.prototype.run = async function (url) {
    // 1) 마스터 목록이면 1080p 이하에서 가장 좋은 화질을 고름
    let { body, url: base } = await this.get(url);
    if (!/^#EXTM3U/.test(body.trim())) throw new Error('재생목록 아님');
    if (/#EXT-X-STREAM-INF/.test(body)) {
      const lines = body.split(/\r?\n/), vs = [];
      for (let i = 0; i < lines.length; i++) {
        if (!lines[i].startsWith('#EXT-X-STREAM-INF')) continue;
        const bw = +((/BANDWIDTH=(\d+)/.exec(lines[i]) || [])[1] || 0);
        const h = +((/RESOLUTION=\d+x(\d+)/.exec(lines[i]) || [])[1] || 0);
        let j = i + 1; while (j < lines.length && (!lines[j].trim() || lines[j].startsWith('#'))) j++;
        if (lines[j]) vs.push({ bw, h, u: new URL(lines[j].trim(), base).href });
      }
      const ok = vs.filter((v) => !v.h || v.h <= 1080);
      const pick = (ok.length ? ok : vs).sort((a, b) => b.bw - a.bw)[0];
      if (!pick) throw new Error('화질 목록 없음');
      this.height = pick.h;
      ({ body, url: base } = await this.get(pick.u));
      this.mediaUrl = pick.u;
    } else this.mediaUrl = url;

    // 2) 조각 목록을 계속 새로 받으며 새 조각을 차례로 넣음
    let first = true;
    while (!this.stopped) {
      if (/#EXT-X-KEY:METHOD=(?!NONE)/.test(body)) throw new Error('암호화된 방송');
      if (/#EXT-X-MAP/.test(body)) throw new Error('fMP4 방식');
      const target = +((/#EXT-X-TARGETDURATION:(\d+(\.\d+)?)/.exec(body) || [])[1] || 6);
      // 조각 길이에 맞춰 여유분을 잡음 (조각이 한꺼번에 오므로 한 조각 이상은 쥐고 있어야 끊기지 않음)
      this.player.bufferSec = Math.max(this.player.bufferSec, target + 0.5);
      this.player.maxLagSec = this.player.bufferSec + target * 3;
      let seq = +((/#EXT-X-MEDIA-SEQUENCE:(\d+)/.exec(body) || [])[1] || 0);
      const segs = [];
      for (const line of body.split(/\r?\n/)) {
        const l = line.trim();
        if (!l || l.startsWith('#')) continue;
        segs.push({ seq: seq++, u: new URL(l, base).href });
      }
      const ended = /#EXT-X-ENDLIST/.test(body);
      // 처음에는 실시간 끝에서 3조각 앞부터 (끊김 대비 여유)
      const fresh = segs.filter((s) => !this.seen.has(s.seq));
      const take = first && !ended ? fresh.slice(-3) : fresh;
      if (first) segs.forEach((s) => { if (!take.includes(s)) this.seen.add(s.seq); });
      first = false;
      for (const s of take) {
        if (this.stopped) return;
        this.seen.add(s.seq);
        try {
          const { body: b } = await this.get(s.u, true);
          if (b[0] !== 0x47) throw new Error('TS 조각 아님');
          this.errors = 0;
          if (!this.player.dead) this.player.push(b);
        } catch (e) {
          if (/TS 조각 아님/.test(e.message)) throw e;
          if (++this.errors >= 4) throw e;
        }
      }
      if (ended && !fresh.length) return;      // 다시보기류: 끝
      if (this.seen.size > 400) this.seen = new Set([...this.seen].slice(-200));
      await new Promise((r) => setTimeout(r, Math.max(1000, (take.length ? target / 2 : target / 3) * 1000)));
      if (this.stopped) return;
      try { ({ body, url: base } = await this.get(this.mediaUrl)); this.errors = 0; }
      catch (e) { if (++this.errors >= 4) throw e; }
    }
  };
  window.HlsFeeder = HlsFeeder;
})();
