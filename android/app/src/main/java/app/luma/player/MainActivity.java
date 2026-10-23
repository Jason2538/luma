package app.luma.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

/** Luma 웹 플레이어를 앱처럼 보여주는 화면 하나짜리 앱이에요. */
public class MainActivity extends Activity {

    private static final String HOME = "https://jason2538.github.io/luma/";

    // 이 주소만 앱 안에서 열어요. Spotify·구글 로그인을 포함한 나머지는 기본 브라우저로 넘겨요.
    // (구글은 앱 안에 들어있는 화면에서 하는 로그인을 막아서, 로그인은 꼭 진짜 브라우저에서 해야 해요.)
    private static final String[] INTERNAL_HOSTS = {
            "jason2538.github.io"
    };

    private WebView web;

    private static boolean isInternal(Uri uri) {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (host == null || !"https".equals(scheme)) return false;
        for (String h : INTERNAL_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0E10);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 일부 로그인 화면이 "wv"(웹뷰) 표시가 있는 브라우저를 거절해서 표시를 지워요.
        s.setUserAgentString(s.getUserAgentString().replace("; wv", "") + " LumaApp/1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isInternal(uri)) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 음악 재생용 보호 미디어(DRM) 요청만 우리 사이트에 허용해요.
                if (!isInternal(request.getOrigin())) {
                    request.deny();
                    return;
                }
                final List<String> ok = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) ok.add(r);
                }
                runOnUiThread(() -> {
                    if (ok.isEmpty()) request.deny();
                    else request.grant(ok.toArray(new String[0]));
                });
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else if (!handleCallback(getIntent())) {
            web.loadUrl(HOME);
        }
    }

    /** 브라우저에서 로그인을 마치고 luma://callback?code=... 로 돌아오면, 그 결과를 Luma 화면에 넘겨서 로그인을 끝내요. */
    private boolean handleCallback(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"luma".equals(data.getScheme()) || !"callback".equals(data.getHost())) {
            return false;
        }
        String query = data.getEncodedQuery();
        web.loadUrl(HOME + (query == null ? "" : "?" + query));
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCallback(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
