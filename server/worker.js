/* Luma 친구 동기화 서버 (Cloudflare Workers 무료 요금제)
   친구가 Luma를 켜 두지 않고 Spotify로만 들어도, 1분마다(20초 간격으로 3번) Spotify에 물어봐서
   지금 듣는 곡과 최근 들은 곡을 Luma 친구 서버(Firebase Realtime Database /now/{id})에 올려요.

   필요한 설정 (README의 "친구 동기화 서버" 참고):
     - KV 네임스페이스를 만들어 변수 이름 KV 로 연결
     - 트리거 → 크론 트리거: * * * * *  (1분마다)
   로그인 열쇠(읽기 전용: 재생 상태·최근 재생만)는 이 서버의 KV에만 저장돼요. */

const CLIENT_ID = '4abe61feeed849bc83968e79adf41cc2';
const FB_KEY = 'AIzaSyBLYoPpD1lLZ4FnTj6VZKw676kHXvuouec';
const RTDB = 'https://luma-friends-default-rtdb.asia-southeast1.firebasedatabase.app';
const SITE = 'https://jason2538.github.io/';
const MAX_USERS = 8;
const ROUNDS = 3, GAP = 20000;

const CORS = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type' };
const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'Content-Type': 'application/json', ...CORS } });
const sleep = ms => new Promise(r => setTimeout(r, ms));
const rtKey = id => String(id).replace(/[.$#\[\]\/%]/g, c => '%' + c.charCodeAt(0).toString(16).toUpperCase());

const kvGet = async (env, k) => { const v = await env.KV.get(k); try { return v ? JSON.parse(v) : null; } catch { return null; } };
const kvPut = (env, k, v) => env.KV.put(k, JSON.stringify(v));

/* ---------- Spotify ---------- */
async function spRefresh(refresh){
  const r = await fetch('https://accounts.spotify.com/api/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refresh, client_id: CLIENT_ID }) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(j.error_description || j.error || 'token ' + r.status), { status: r.status });
  return j;
}
async function spGet(path, access){
  const r = await fetch('https://api.spotify.com/v1' + path, { headers: { Authorization: 'Bearer ' + access } });
  if (r.status === 204) return { status: 204, body: null };
  return { status: r.status, body: await r.json().catch(() => null) };
}
/* 사용자 열쇠가 만료됐으면 새로 받아 KV에 저장 */
async function userAccess(env, u){
  if (u.access && Date.now() < u.exp - 90000) return u.access;
  const j = await spRefresh(u.refresh);
  u.access = j.access_token; u.exp = Date.now() + (j.expires_in || 3600) * 1000;
  if (j.refresh_token) u.refresh = j.refresh_token;
  delete u.err;
  await kvPut(env, 'u:' + u.id, u);
  return u.access;
}

/* ---------- Firebase (익명 로그인) ---------- */
async function fbToken(env){
  const saved = await kvGet(env, 'fb');
  if (saved && saved.tok && Date.now() < saved.exp - 120000) return saved.tok;
  let j = null;
  if (saved && saved.refresh) {
    const r = await fetch('https://securetoken.googleapis.com/v1/token?key=' + FB_KEY, { method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', Referer: SITE }, body: new URLSearchParams({ grant_type: 'refresh_token', refresh_token: saved.refresh }) });
    if (r.ok) { const x = await r.json(); j = { tok: x.id_token, refresh: x.refresh_token, exp: Date.now() + (+x.expires_in || 3600) * 1000 }; }
  }
  if (!j) {
    const r = await fetch('https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=' + FB_KEY, { method: 'POST',
      headers: { 'Content-Type': 'application/json', Referer: SITE }, body: JSON.stringify({ returnSecureToken: true }) });
    const x = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error('firebase auth ' + r.status + ' ' + ((x.error && x.error.message) || ''));
    j = { tok: x.idToken, refresh: x.refreshToken, exp: Date.now() + (+x.expiresIn || 3600) * 1000 };
  }
  await kvPut(env, 'fb', j);
  return j.tok;
}

/* ---------- 날짜 (사용자 시간대 기준) ---------- */
const pad2 = n => String(n).padStart(2, '0');
function periods(tzMin, now){
  const d = new Date(now + tzMin * 60000);                         // UTC 메서드로 읽으면 사용자 현지 시각
  const y = d.getUTCFullYear(), m = d.getUTCMonth(), day = d.getUTCDate();
  const key = (yy, mm, dd) => { const x = new Date(Date.UTC(yy, mm, dd)); return x.getUTCFullYear() + '-' + pad2(x.getUTCMonth() + 1) + '-' + pad2(x.getUTCDate()); };
  const start = (yy, mm, dd) => Date.UTC(yy, mm, dd) - tzMin * 60000;
  const wd = (d.getUTCDay() + 6) % 7;                              // 월요일 = 0
  return { d: [key(y, m, day), start(y, m, day)], w: [key(y, m, day - wd), start(y, m, day - wd)], m: [key(y, m, 1), start(y, m, 1)] };
}

/* 기준(base: Luma가 마지막으로 올린 값)에 그 뒤 Spotify에서 들은 곡을 더해요 */
function mergeStats(base, plays, tzMin, now){
  const P = periods(tzMin, now), b = (base && base.st) || {}, baseAt = (base && base.at) || 0;
  const sum = from => { let ms = 0, n = 0; for (const p of plays) if (p.at >= from) { ms += p.ms; n++; } return [ms, n]; };
  const one = (k, msK, nK) => {
    const same = b[k] === P[k][0];
    const [ms, n] = sum(same ? baseAt : P[k][1]);
    return [(same ? b[msK] || 0 : 0) + ms, (same ? b[nK] || 0 : 0) + n];
  };
  const [tMs, tP] = one('d', 'tMs', 'tP'), [wMs, wP] = one('w', 'wMs', 'wP'), [mMs, mP] = one('m', 'mMs', 'mP');
  const st = { d: P.d[0], w: P.w[0], m: P.m[0], tMs, wMs, mMs, tP, wP, mP, wU: b.wU || 0, stk: b.stk || 0 };
  const fresh = plays.filter(p => p.at >= baseAt).sort((x, y) => y.at - x.at);
  const rec = fresh.concat((base && base.rec) || []).slice(0, 15);
  return { st, rec };
}

/* ---------- 1분마다 ---------- */
async function poll(env){
  const ids = (await kvGet(env, 'ids')) || [];
  if (!ids.length) return 'no users';
  const users = (await Promise.all(ids.map(id => kvGet(env, 'u:' + id)))).filter(u => u && u.refresh && !u.err);
  if (!users.length) return 'no active users';
  const fb = await fbToken(env);
  const log = [];
  for (let round = 0; round < ROUNDS; round++) {
    if (round) await sleep(GAP);
    const r = await fetch(`${RTDB}/now.json?auth=${fb}`);
    if (!r.ok) { log.push('rtdb read ' + r.status); continue; }
    const live = (await r.json()) || {};
    const now = Date.now(), patch = {};
    await Promise.all(users.map(async u => {
      try {
        const key = rtKey(u.id), cur = live[key];
        // Luma가 켜져 있어서 직접 올리는 중이면 건드리지 않아요
        if (cur && cur.src !== 'srv' && now - (cur.at || 0) < 90000) return;
        const access = await userAccess(env, u);
        const p = await spGet('/me/player?additional_types=track', access);
        if (p.status === 401) { u.exp = 0; return; }
        if (p.status !== 200 && p.status !== 204) return;
        const it = p.body && p.body.item, priv = p.body && p.body.device && p.body.device.is_private_session;
        const playing = !!(it && it.type === 'track' && p.body.is_playing && !priv);
        // 기준값: Luma가 올린 마지막 기록 (서버가 덮어써도 b 안에 계속 들고 다녀요)
        const base = cur ? (cur.src === 'srv' ? cur.b : { st: cur.st || null, rec: cur.rec || [], at: cur.at || 0, tz: cur.tz }) : null;
        const b = base || { st: null, rec: [], at: u.since || now, tz: u.tz };
        const tz = typeof b.tz === 'number' ? b.tz : (typeof u.tz === 'number' ? u.tz : 540);
        const rec0 = { v: 'srv', src: 'srv', sid: u.id, name: u.name || u.id, img: u.img || '', b, at: { '.sv': 'timestamp' } };
        let rec = rec0;
        if (u.now !== false && it && it.type === 'track' && !priv) {
          const imgs = (it.album && it.album.images) || [];
          Object.assign(rec, { uri: it.uri, id: it.id, n: it.name, a: (it.artists || []).map(x => x.name).join(', '),
            img_t: (imgs[imgs.length - 1] || {}).url || '', big: (imgs[0] || {}).url || '', dur: it.duration_ms || 0, pos: p.body.progress_ms || 0, playing });
        }
        else if (u.now !== false && !priv && cur && cur.uri) {      // 아무것도 안 틀고 있으면: 마지막 곡을 "멈춤"으로 남겨요
          for (const k of ['uri', 'id', 'n', 'a', 'img_t', 'big', 'dur', 'pos']) if (cur[k] !== undefined) rec[k] = cur[k];
          rec.playing = false;
        }
        // 재생 기록: 곡이 바뀌었거나 10분이 지났을 때만 Spotify 최근 재생을 다시 읽어요
        const prevSrv = cur && cur.src === 'srv' ? cur : null;
        const needRp = u.stats !== false && (!prevSrv || prevSrv.uri !== rec.uri || now - (prevSrv.rp || 0) > 600000);
        if (u.stats === false) { /* 기록 공유 안 함 */ }
        else if (needRp) {
          const q = await spGet('/me/player/recently-played?limit=20', access);
          if (q.status === 200 && q.body && q.body.items) {
            const plays = q.body.items.filter(x => x && x.track && x.played_at).map(x => {
              const t = x.track, im = (t.album && t.album.images) || [], ms = t.duration_ms || 0;
              return { id: t.id, n: t.name, a: (t.artists || []).map(y => y.name).join(', '), img: (im[im.length - 1] || {}).url || '', at: Date.parse(x.played_at) - ms, ms };
            });
            Object.assign(rec, mergeStats(b, plays, tz, now)); rec.rp = now;
          } else if (prevSrv) { rec.st = prevSrv.st || null; rec.rec = prevSrv.rec || []; rec.rp = prevSrv.rp || 0; }
        } else { rec.st = prevSrv.st || null; rec.rec = prevSrv.rec || []; rec.rp = prevSrv.rp || 0; }
        if (!rec.st && b.st) { rec.st = b.st; rec.rec = b.rec || []; }
        // 바뀐 게 없으면 40초에 한 번만 다시 올려요 (멈춰 있으면 안 올려요)
        if (prevSrv) {
          const same = prevSrv.uri === rec.uri && !!prevSrv.playing === !!rec.playing && (prevSrv.rp || 0) === (rec.rp || 0);
          const drift = Math.abs((rec.pos || 0) - ((prevSrv.pos || 0) + (prevSrv.playing ? now - (prevSrv.at || 0) : 0)));
          if (same && (!rec.playing || (drift < 4000 && now - (prevSrv.at || 0) < 40000))) return;
        } else if (!rec.uri && !rec.st && !cur) return;
        for (const k of Object.keys(rec)) if (rec[k] === undefined || rec[k] === null) delete rec[k];
        patch[key] = rec;
      } catch (e) {
        if (e.status === 400 || e.status === 401) { u.err = 'relogin'; u.access = ''; await kvPut(env, 'u:' + u.id, u).catch(() => {}); }
        log.push(u.id + ': ' + e.message);
      }
    }));
    const keys = Object.keys(patch);
    if (keys.length) {
      const w = await fetch(`${RTDB}/now.json?auth=${fb}&print=silent`, { method: 'PATCH', body: JSON.stringify(patch) });
      log.push(`round ${round}: wrote ${keys.length} (${w.status})`);
    }
  }
  return log.join(' | ') || 'ok';
}

/* ---------- 요청 처리 ---------- */
async function whoIs(access){
  const m = await spGet('/me', access);
  if (m.status !== 200 || !m.body || !m.body.id) throw Object.assign(new Error('Spotify 계정을 확인하지 못했어요'), { status: 401 });
  return m.body;
}
async function handle(req, env){
  const url = new URL(req.url), path = url.pathname.replace(/\/+$/, '') || '/';
  if (req.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS });
  if (!env.KV) return json({ ok: false, error: 'KV 네임스페이스가 연결되지 않았어요 (변수 이름: KV)' }, 500);
  if (req.method === 'GET' && path === '/') {
    const ids = (await kvGet(env, 'ids')) || [];
    return json({ ok: true, service: 'luma-sync', users: ids.length });
  }
  if (req.method === 'GET' && path === '/status') {
    const u = await kvGet(env, 'u:' + (url.searchParams.get('id') || ''));
    return json({ ok: true, on: !!(u && u.refresh && !u.err), err: (u && u.err) || '' });
  }
  if (req.method === 'GET' && path === '/run') return json({ ok: true, log: await poll(env) });   // 설정 확인용: 지금 한 번 돌려 보기
  if (req.method !== 'POST') return json({ ok: false, error: 'not found' }, 404);
  const body = await req.json().catch(() => ({}));
  if (path === '/register') {
    if (!body.refresh_token) return json({ ok: false, error: 'refresh_token 필요' }, 400);
    const j = await spRefresh(String(body.refresh_token));
    const meJ = await whoIs(j.access_token);
    const ids = (await kvGet(env, 'ids')) || [];
    if (!ids.includes(meJ.id)) {
      if (ids.length >= MAX_USERS) return json({ ok: false, error: '등록할 수 있는 인원이 다 찼어요' }, 403);
      ids.push(meJ.id); await kvPut(env, 'ids', ids);
    }
    const old = await kvGet(env, 'u:' + meJ.id);
    await kvPut(env, 'u:' + meJ.id, { id: meJ.id, name: meJ.display_name || meJ.id, img: ((meJ.images || [])[0] || {}).url || '',
      refresh: j.refresh_token || body.refresh_token, access: j.access_token, exp: Date.now() + (j.expires_in || 3600) * 1000,
      tz: typeof body.tz === 'number' ? body.tz : 540, now: body.now !== false, stats: body.stats !== false, since: (old && old.since) || Date.now() });
    return json({ ok: true, id: meJ.id });
  }
  if (path === '/prefs' || path === '/unregister') {
    const meJ = await whoIs(String(body.access_token || ''));
    const u = await kvGet(env, 'u:' + meJ.id);
    if (path === '/unregister') {
      await env.KV.delete('u:' + meJ.id);
      const ids = ((await kvGet(env, 'ids')) || []).filter(x => x !== meJ.id); await kvPut(env, 'ids', ids);
      return json({ ok: true });
    }
    if (!u) return json({ ok: false, error: '등록돼 있지 않아요' }, 404);
    u.now = body.now !== false; u.stats = body.stats !== false; if (typeof body.tz === 'number') u.tz = body.tz;
    await kvPut(env, 'u:' + meJ.id, u);
    return json({ ok: true });
  }
  return json({ ok: false, error: 'not found' }, 404);
}

export default {
  async fetch(req, env){
    try { return await handle(req, env); }
    catch (e) { return json({ ok: false, error: String(e.message || e) }, e.status || 500); }
  },
  async scheduled(event, env, ctx){
    ctx.waitUntil(poll(env).catch(e => console.log('poll error', e.message)));
  }
};
