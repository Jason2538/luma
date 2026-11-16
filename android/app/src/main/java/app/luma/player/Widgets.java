package app.luma.player;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 홈 화면 위젯 두 가지(지금 듣는 곡 / 싱크 가사)의 상태를 들고 그려 주는 곳이에요.
 * Luma 화면이 곡·재생 위치·가사(줄마다 시각)를 넘겨주면, 줄을 넘기고 진행바를 움직이는 건 여기서 직접 해요.
 */
final class Widgets {
    static final String A_PREV = "app.luma.player.W_PREV";
    static final String A_TOGGLE = "app.luma.player.W_TOGGLE";
    static final String A_NEXT = "app.luma.player.W_NEXT";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean alive, has, empty = true, playing, ticking;
    private static long hasAt, pos, dur, at, lastProg = -1, lastJsTick;
    private static String title = "", artist = "", artUrl = "", plain = "", msg = "", src = "", lyId = "";
    private static long[] times = new long[0];
    private static String[] texts = new String[0];
    private static int lastIdx = -2, page, fontSp = 20;
    private static String anim = "slide", lastSig = "";
    private static Bitmap art;

    private Widgets() { }

    /** 앱(Luma 화면)이 켜졌을 때 */
    static void appStarted(Context c) {
        MAIN.post(() -> {
            app = c.getApplicationContext();
            alive = true;
            renderAll(app);
            startTicker();
        });
    }

    /** 앱이 꺼졌을 때: 위젯을 "눌러서 열기" 상태로 돌려요 */
    static void appGone(Context c) {
        final Context a = c.getApplicationContext();
        MAIN.post(() -> {
            alive = false;
            lastSig = "";
            empty = true;
            playing = false;
            times = new long[0];
            texts = new String[0];
            lyId = "";
            try { renderAll(a); } catch (Exception ignored) { }
        });
    }

    static boolean hasWidgets(Context c) {
        long now = SystemClock.elapsedRealtime();
        if (hasAt == 0 || now - hasAt > 4000) {
            hasAt = now;
            try {
                AppWidgetManager m = AppWidgetManager.getInstance(c);
                has = m.getAppWidgetIds(new ComponentName(c, NowWidget.class)).length > 0
                        || m.getAppWidgetIds(new ComponentName(c, LyricsWidget.class)).length > 0;
            } catch (Exception e) {
                has = false;
            }
        }
        return has;
    }

    static void widgetsChanged() { hasAt = 0; }

    /** Luma 화면이 알려 주는 상태 (JSON) */
    static void update(final Context c, final String json) {
        MAIN.post(() -> {
            app = c.getApplicationContext();
            try {
                JSONObject o = new JSONObject(json);
                fontSp = Math.max(12, Math.min(40, o.optInt("fs", fontSp)));
                anim = o.optString("an", anim);
                if (o.optBoolean("empty")) {
                    empty = true;
                    playing = false;
                } else {
                    empty = false;
                    title = o.optString("title", "");
                    artist = o.optString("artist", "");
                    playing = o.optBoolean("playing");
                    pos = o.optLong("pos", 0);
                    dur = o.optLong("dur", 0);
                    at = SystemClock.elapsedRealtime();
                    plain = o.optString("plain", "");
                    msg = o.optString("msg", "");
                    src = o.optString("src", "");
                    String id = o.optString("lyId", "");
                    JSONArray ls = o.optJSONArray("lines");
                    if (ls != null) {
                        int n = ls.length();
                        long[] t = new long[n];
                        String[] s = new String[n];
                        for (int i = 0; i < n; i++) {
                            JSONArray l = ls.getJSONArray(i);
                            t[i] = l.optLong(0);
                            s[i] = l.optString(1, "");
                        }
                        times = t;
                        texts = s;
                    } else if (!id.equals(lyId)) {
                        times = new long[0];
                        texts = new String[0];
                    }
                    lyId = id;
                    String a = o.optString("art", "");
                    if (!a.equals(artUrl)) {
                        artUrl = a;
                        art = null;
                        loadArt(a);
                    }
                }
            } catch (Exception e) {
                return;
            }
            // 위치만 다시 알려 준 거면 화면은 그대로 두고 시계만 맞춰요 (줄 넘김·진행바는 ticker가 해요)
            String sig = empty + "|" + title + "|" + artist + "|" + playing + "|" + lyId + "|" + times.length + "|" + plain.length() + "|" + msg + "|" + fontSp + "|" + anim + "|" + artUrl;
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                renderAll(app);
            } else if (!empty && hasWidgets(app)) {
                try {
                    int idx = times.length > 0 ? curIdx() : -2;
                    if (idx != lastIdx || !playing) partial(app, idx != lastIdx);     // 멈춘 채로 위치를 옮겼을 때
                } catch (Exception ignored) { }
            }
            startTicker();
        });
    }

    private static long curPos() {
        long p = playing ? pos + (SystemClock.elapsedRealtime() - at) : pos;
        if (dur > 0 && p > dur) p = dur;
        return Math.max(0, p);
    }

    private static int curIdx() {
        long p = curPos() + 200;
        int lo = 0, hi = times.length - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= p) { idx = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return idx;
    }

    private static String line(int i) {
        return i >= 0 && i < texts.length ? texts[i] : "";
    }

    private static void startTicker() {
        if (ticking) return;
        ticking = true;
        MAIN.postDelayed(Widgets::tick, 250);
    }

    private static void tick() {
        if (!alive || app == null) { ticking = false; return; }
        boolean on = hasWidgets(app);
        long now = SystemClock.elapsedRealtime();
        if (now - lastJsTick >= 2000) { lastJsTick = now; MainActivity.tick(); }   // Luma 화면이 뒤에 있어도 상태를 계속 확인하게
        if (on) {
            if (!empty && playing) {
                try {
                    int idx = times.length > 0 ? curIdx() : -2;
                    long prog = dur > 0 ? curPos() * 200 / dur : 0;
                    if (idx != lastIdx || prog != lastProg) partial(app, idx != lastIdx);
                } catch (Exception ignored) { }
            }
        }
        MAIN.postDelayed(Widgets::tick, on && playing && !empty ? 250 : 2000);
    }

    /** 가사 줄과 진행바만 가볍게 바꿔요 (커버 그림은 다시 안 보내요) */
    private static void partial(Context c, boolean lyricsToo) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int prog = dur > 0 ? (int) (curPos() * 200 / dur) : 0;
        lastProg = prog;
        int[] a = m.getAppWidgetIds(new ComponentName(c, NowWidget.class));
        if (a.length > 0) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_now);
            v.setProgressBar(R.id.w_prog, 200, prog, false);
            m.partiallyUpdateAppWidget(a, v);
        }
        int[] b = m.getAppWidgetIds(new ComponentName(c, LyricsWidget.class));
        if (b.length > 0) {
            RemoteViews v = new RemoteViews(c.getPackageName(), lyLayout());
            v.setProgressBar(R.id.w_prog, 200, prog, false);
            if (lyricsToo) setLyrics(v, true);
            m.partiallyUpdateAppWidget(b, v);
        }
        if (lyricsToo) lastIdx = times.length > 0 ? curIdx() : -2;
    }

    private static int lyLayout() {
        switch (anim) {
            case "none": return R.layout.widget_lyrics_none;
            case "fade": return R.layout.widget_lyrics_fade;
            case "slow": return R.layout.widget_lyrics_slow;
            default: return R.layout.widget_lyrics_slide;
        }
    }

    private static final int[][] PAGE = {
            {R.id.w_a0, R.id.w_a1, R.id.w_a2, R.id.w_a3},
            {R.id.w_b0, R.id.w_b1, R.id.w_b2, R.id.w_b3}
    };

    private static void fillPage(RemoteViews v, int p, int i) {
        float small = Math.max(11f, fontSp * 0.68f);
        v.setTextViewText(PAGE[p][0], line(i - 1));
        v.setTextViewText(PAGE[p][1], i < 0 ? "♪" : line(i));
        v.setTextViewText(PAGE[p][2], line(i + 1));
        v.setTextViewText(PAGE[p][3], line(i + 2));
        v.setTextViewTextSize(PAGE[p][0], TypedValue.COMPLEX_UNIT_SP, small);
        v.setTextViewTextSize(PAGE[p][1], TypedValue.COMPLEX_UNIT_SP, fontSp);
        v.setTextViewTextSize(PAGE[p][2], TypedValue.COMPLEX_UNIT_SP, small);
        v.setTextViewTextSize(PAGE[p][3], TypedValue.COMPLEX_UNIT_SP, small);
    }

    /** flip = 줄이 바뀌었을 때: 안 보이는 쪽에 새 줄을 채우고 넘겨서 넘김 효과가 나게 해요 */
    private static void setLyrics(RemoteViews v, boolean flip) {
        if (!empty && times.length > 0) {
            int i = curIdx();
            v.setViewVisibility(R.id.w_sync, View.VISIBLE);
            v.setViewVisibility(R.id.w_plain, View.GONE);
            if (flip) {
                page = 1 - page;
                fillPage(v, page, i);
            } else {
                fillPage(v, 0, i);
                fillPage(v, 1, i);
            }
            v.setDisplayedChild(R.id.w_sync, page);
        } else {
            v.setViewVisibility(R.id.w_sync, View.GONE);
            v.setViewVisibility(R.id.w_plain, View.VISIBLE);
            String t = empty ? (alive ? "노래를 틀면 가사가 여기에 나와요" : "눌러서 Luma를 열면 가사가 나와요")
                    : !plain.isEmpty() ? plain : msg;
            v.setTextViewText(R.id.w_plain, t);
            v.setTextViewTextSize(R.id.w_plain, TypedValue.COMPLEX_UNIT_SP, Math.max(12f, fontSp * 0.72f));
        }
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent btn(Context c, String action, int code) {
        if (!alive) return open(c);       // 앱이 꺼져 있으면 버튼을 눌러도 먼저 앱을 열어요
        Intent i = new Intent(c, NowWidget.class).setAction(action);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void common(Context c, RemoteViews v) {
        v.setTextViewText(R.id.w_title, empty ? "Luma" : title);
        v.setTextViewText(R.id.w_artist, empty ? (alive ? "재생 중인 곡이 없어요" : "눌러서 Luma 열기") : artist);
        if (art != null && !empty) v.setImageViewBitmap(R.id.w_art, art);
        else v.setImageViewResource(R.id.w_art, R.drawable.widget_art);
        v.setImageViewResource(R.id.w_toggle, playing && !empty ? R.drawable.ic_n_pause : R.drawable.ic_n_play);
        v.setProgressBar(R.id.w_prog, 200, empty || dur <= 0 ? 0 : (int) (curPos() * 200 / dur), false);
        v.setOnClickPendingIntent(R.id.w_root, open(c));
        v.setOnClickPendingIntent(R.id.w_prev, btn(c, A_PREV, 11));
        v.setOnClickPendingIntent(R.id.w_toggle, btn(c, A_TOGGLE, 12));
        v.setOnClickPendingIntent(R.id.w_next, btn(c, A_NEXT, 13));
    }

    /** 위젯 전체를 다시 그려요 */
    static void renderAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        ComponentName now = new ComponentName(c, NowWidget.class), ly = new ComponentName(c, LyricsWidget.class);
        try {
            if (m.getAppWidgetIds(now).length > 0) {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_now);
                common(c, v);
                m.updateAppWidget(now, v);
            }
            if (m.getAppWidgetIds(ly).length > 0) {
                RemoteViews v = new RemoteViews(c.getPackageName(), lyLayout());
                common(c, v);
                setLyrics(v, false);
                v.setTextViewText(R.id.w_src, empty ? "" : src);
                m.updateAppWidget(ly, v);
            }
            lastIdx = times.length > 0 ? curIdx() : -2;
        } catch (Exception ignored) {
        }
    }

    /** 위젯 버튼(이전 / 재생·멈춤 / 다음) */
    static void onAction(Context c, String a) {
        if (!alive) return;
        if (A_PREV.equals(a)) MainActivity.dispatch("prev");
        else if (A_NEXT.equals(a)) MainActivity.dispatch("next");
        else if (A_TOGGLE.equals(a)) {
            MainActivity.dispatch(playing ? "pause" : "play");
            MAIN.post(() -> {
                lastSig = "";              // 누르자마자 모양부터 바꿔요 (진짜 상태는 곧 Luma가 다시 알려 줘요)
                pos = curPos();
                at = SystemClock.elapsedRealtime();
                playing = !playing;
                renderAll(c.getApplicationContext());
                startTicker();
            });
        }
    }

    private static void loadArt(final String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            Bitmap out = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                Bitmap bmp;
                try (InputStream in = c.getInputStream()) { bmp = BitmapFactory.decodeStream(in); }
                if (bmp != null) {
                    int s = 240;
                    Bitmap sc = Bitmap.createScaledBitmap(bmp, s, s, true);
                    out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
                    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                    p.setShader(new BitmapShader(sc, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                    new Canvas(out).drawRoundRect(new RectF(0, 0, s, s), s * 0.16f, s * 0.16f, p);
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final Bitmap result = out;
            MAIN.post(() -> {
                if (url.equals(artUrl) && result != null && app != null) {
                    art = result;
                    renderAll(app);
                }
            });
        }).start();
    }
}
