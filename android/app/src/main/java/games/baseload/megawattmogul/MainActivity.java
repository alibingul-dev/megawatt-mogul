package games.baseload.megawattmogul;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.window.OnBackInvokedDispatcher;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.google.android.play.core.review.ReviewInfo;
import com.google.android.play.core.review.ReviewManager;
import com.google.android.play.core.review.ReviewManagerFactory;

/* The whole game ships inside the app (assets/www). It is served from
   https://appassets.androidplatform.net so it runs as a normal secure web page
   with its own localStorage, entirely on the phone. Nothing is fetched from the
   network; any outside link (the Play page) opens in the right app instead. */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/www/index.html";

    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        web.setBackgroundColor(0xFF8CCDF6);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return loader.shouldInterceptRequest(req.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openOutside(u);
                return true;
            }
        });

        // the page asks for these: share sheet (WebView has no navigator.share) and leaving after the exit card
        web.addJavascriptInterface(new Bridge(), "MMAndroid");

        // Back goes to the page first: it closes an open window or asks before leaving.
        // From Android 16 (target 36) onBackPressed is no longer called, so register the callback too.
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::askPageBack);
        }

        hideBars();
        web.loadUrl(START);
    }

    private void askPageBack() {
        if (web == null) { finish(); return; }
        // the page answers "1" when it handled back itself; anything else (page not ready) leaves
        web.evaluateJavascript("(function(){try{return mmBack()?'1':'0'}catch(e){return '0'}})()", r -> {
            if (!"\"1\"".equals(r)) finish();
        });
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        askPageBack();
    }

    public class Bridge {
        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, text == null ? "" : text);
                    startActivity(Intent.createChooser(i, null));
                } catch (Exception ignored) { }
            });
        }

        /* Play's own rating sheet. Play decides whether to show it (quota); if the request fails,
           open the store page instead so the button never does nothing. */
        @JavascriptInterface
        public void review() {
            runOnUiThread(() -> {
                try {
                    final ReviewManager m = ReviewManagerFactory.create(MainActivity.this);
                    m.requestReviewFlow().addOnCompleteListener(t -> {
                        if (t.isSuccessful()) {
                            ReviewInfo info = t.getResult();
                            m.launchReviewFlow(MainActivity.this, info);
                        } else {
                            openOutside(Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName()));
                        }
                    });
                } catch (Exception e) {
                    openOutside(Uri.parse("https://play.google.com/store/apps/details?id=" + getPackageName()));
                }
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(() -> {
                if (web != null) web.evaluateJavascript("try{save()}catch(e){}", v -> finish());
                else finish();
            });
        }
    }

    private void openOutside(Uri u) {
        try {
            if ("play.google.com".equals(u.getHost()) && u.getQueryParameter("id") != null) {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=" + u.getQueryParameter("id"))));
                return;
            }
        } catch (ActivityNotFoundException ignored) { }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) { }
    }

    @SuppressWarnings("deprecation")
    private void hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideBars();
    }

    @Override
    protected void onPause() {
        // the page saves on pagehide, which a backgrounded app may never get; save now
        if (web != null) {
            web.evaluateJavascript("try{save()}catch(e){}", null);
            web.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        hideBars();
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
