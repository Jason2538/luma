package app.luma.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Luma에서 음악이 재생되는 동안 앱을 내리거나 화면을 꺼도 계속 재생되게 하고,
 * 알림창·잠금화면에 커버와 재생 버튼(이전 / 재생·일시정지 / 다음)을 보여주는 서비스예요.
 * 실제 소리는 MainActivity의 웹 화면(Spotify 재생기)에서 나고, 이 서비스는 앱이 꺼지지 않게 붙잡아 둬요.
 */
public class PlaybackService extends Service {

    private static final String CHANNEL = "playback";
    private static final int NOTIFICATION_ID = 7;
    private static final String A_PREV = "app.luma.player.PREV";
    private static final String A_TOGGLE = "app.luma.player.TOGGLE";
    private static final String A_NEXT = "app.luma.player.NEXT";

    private static PlaybackService instance;
    private static String pending;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaSession session;
    private String title = "", artist = "", album = "", art = "", artLoaded = "";
    private boolean playing;
    private long pos, dur, at;
    private Bitmap artBitmap;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    /** 웹 화면이 재생 상태를 알려줄 때 불러요 (어느 스레드에서 불러도 돼요). */
    static void update(final Context ctx, final String json) {
        MAIN.post(() -> {
            if (instance != null) {
                instance.apply(json);
                return;
            }
            pending = json;
            boolean isPlaying = false;
            try { isPlaying = new JSONObject(json).optBoolean("playing"); } catch (Exception ignored) { }
            if (!isPlaying) return;
            try {
                Intent i = new Intent(ctx, PlaybackService.class);
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
                else ctx.startService(i);
            } catch (Exception ignored) {
                // 백그라운드에서 시작이 막히면 다음 업데이트 때 다시 시도해요.
            }
        });
    }

    static void stop() {
        MAIN.post(() -> {
            pending = null;
            if (instance != null) instance.shutdown();
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "재생 중인 음악", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("알림창과 잠금화면에서 Luma 재생을 조작해요");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }

        session = new MediaSession(this, "Luma");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.dispatch("play"); }
            @Override public void onPause() { MainActivity.dispatch("pause"); }
            @Override public void onStop() { MainActivity.dispatch("pause"); }
            @Override public void onSkipToNext() { MainActivity.dispatch("next"); }
            @Override public void onSkipToPrevious() { MainActivity.dispatch("prev"); }
            @Override public void onSeekTo(long p) { MainActivity.dispatch("seek:" + p); }
        });
        session.setActive(true);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luma:playback");
        wake.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "luma:stream");
            wifi.setReferenceCounted(false);
        }

        goForeground();
        if (pending != null) apply(pending);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_PREV.equals(a)) MainActivity.dispatch("prev");
        else if (A_NEXT.equals(a)) MainActivity.dispatch("next");
        else if (A_TOGGLE.equals(a)) MainActivity.dispatch(playing ? "pause" : "play");
        return START_NOT_STICKY;
    }

    private void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            title = o.optString("title", "");
            artist = o.optString("artist", "");
            album = o.optString("album", "");
            playing = o.optBoolean("playing");
            pos = o.optLong("pos", 0);
            dur = o.optLong("dur", 0);
            at = SystemClock.elapsedRealtime();
            String newArt = o.optString("art", "");
            if (!newArt.equals(art)) {
                art = newArt;
                artBitmap = null;
                loadArt(newArt);
            }
        } catch (Exception e) {
            return;
        }
        refresh();
        if (playing) {
            if (!wake.isHeld()) wake.acquire(4 * 60 * 60 * 1000L);
            if (wifi != null && !wifi.isHeld()) wifi.acquire();
        } else {
            if (wake.isHeld()) wake.release();
            if (wifi != null && wifi.isHeld()) wifi.release();
        }
    }

    private void refresh() {
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (artBitmap != null) m.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artBitmap);
        session.setMetadata(m.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? 1f : 0f, at)
                .build());
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIFICATION_ID, build());
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title.isEmpty() ? "Luma" : title)
                .setContentText(artist)
                .setSubText(album)
                .setContentIntent(content)
                .setOngoing(playing)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_prev), "이전 곡", action(A_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play),
                        playing ? "일시정지" : "재생", action(A_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_next), "다음 곡", action(A_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (artBitmap != null) b.setLargeIcon(artBitmap);
        return b.build();
    }

    private void goForeground() {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIFICATION_ID, n);
    }

    private void loadArt(final String url) {
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            Bitmap bmp = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) { bmp = BitmapFactory.decodeStream(in); }
                if (bmp != null && bmp.getWidth() > 512) bmp = Bitmap.createScaledBitmap(bmp, 512, 512, true);
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
            final Bitmap result = bmp;
            MAIN.post(() -> {
                if (instance == this && url.equals(art) && result != null) {
                    artBitmap = result;
                    artLoaded = url;
                    refresh();
                }
            });
        }).start();
    }

    private void shutdown() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 최근 앱 목록에서 Luma를 밀어서 닫으면 재생도 끝나요.
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (session != null) session.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
