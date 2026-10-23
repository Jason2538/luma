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

    // 이 주소들은 앱 안에서 열고, 나머지 링크는 기본 브라우저로 넘겨요.
    private static final String[] INTERNAL_HOSTS = {
            "jason2538.github.io",
            "spotify.com",
            "accounts.google.com",
            "facebook.com",
            "appleid.apple.com",
            "apple.com"
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
        } else {
            web.loadUrl(HOME);
        }
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
