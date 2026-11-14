package app.reborn.iptv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import android.content.Intent;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Reborn IPTV for Fire TV.
 * Full-screen WebView around the live Vercel app, plus remote-control support.
 * Because it loads the live site, updates pushed to Vercel appear here without reinstalling.
 */
public class MainActivity extends Activity {

    /** Reborn's own address; its /api/proxy calls are answered on the box instead of on Vercel. */
    private static final String APP_HOST = android.net.Uri.parse(BuildConfig.START_URL).getHost();

    private FrameLayout root;
    private WebView web;
    private FrameLayout fullscreenHolder;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private LinearLayout offlineView;
    private ProgressBar spinner;
    private String navScript;
    private long lastBackPress = 0;
    private boolean mainFrameFailed = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();

        navScript = loadAsset("tvnav.js");

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0B0F17"));

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0B0F17"));
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);           // keeps login / favourites in localStorage
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Many IPTV streams are plain http:// even when the app is https://
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        s.setUserAgentString(s.getUserAgentString() + " RebornFireTV/1.0");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mainFrameFailed = false;
                spinner.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                spinner.setVisibility(View.GONE);
                if (!mainFrameFailed) {
                    offlineView.setVisibility(View.GONE);
                    injectNav();
                }
            }

            // Catch video streams the web player tries to load and play them natively instead.
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse direct = ProviderProxy.handle(req, APP_HOST);
                if (direct != null) return direct;
                String stream = StreamDetector.find(req.getUrl().toString());
                if (stream != null) {
                    handleStream(stream);
                    // Give the web player an empty answer so it stops trying.
                    return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                            corsHeaders(), new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String url) {
                if (url != null && url.equals(view.getUrl())) showOffline();
            }
        });

        // Reborn registers a service worker; on Android, requests from a page it controls
        // skip the WebViewClient above and come through here instead.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                    WebResourceResponse direct = ProviderProxy.handle(req, APP_HOST);
                    if (direct != null) return direct;
                    String stream = StreamDetector.find(req.getUrl().toString());
                    if (stream != null) {
                        handleStream(stream);
                        return new WebResourceResponse("text/plain", "utf-8", 204, "No Content",
                                corsHeaders(), new ByteArrayInputStream(new byte[0]));
                    }
                    return null;
                }
            });
        }

        // Backup route: the injected script reports streams the web player starts.
        web.addJavascriptInterface(new NativeBridge(this), "RebornNative");

        web.setWebChromeClient(new WebChromeClient() {
            // Lets the web player go properly full-screen.
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                fullscreenHolder.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenHolder.setVisibility(View.VISIBLE);
                web.setVisibility(View.GONE);
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // Avoids the grey play-button placeholder before a stream starts.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });

        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fullscreenHolder = new FrameLayout(this);
        fullscreenHolder.setBackgroundColor(Color.BLACK);
        fullscreenHolder.setVisibility(View.GONE);
        root.addView(fullscreenHolder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(spinner, sp);

        offlineView = buildOfflineView();
        root.addView(offlineView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            // No cache clearing: Vercel marks the page "check for updates every time",
            // so a new Reborn version still loads straight away, while unchanged files
            // are reused (saves data on Vercel's allowance).
            loadHome();
        }
    }

    private void loadHome() {
        if (!isOnline()) { showOffline(); return; }
        offlineView.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(BuildConfig.START_URL);
        web.requestFocus();
    }

    private String lastStream = null;
    private long lastStreamAt = 0;
    private volatile boolean playerOpen = false;

    /**
     * Opens the native player. Ignores repeats while the player is open
     * (the web player retries and tries backup links when it gets no data).
     */
    void openPlayer(String stream) {
        openPlayer(stream, null, -1);
    }

    void openPlayer(String stream, String channelsJson, int index) {
        runOnUiThread(() -> {
            stopPreview();
            playerOpen = true;
            lastStream = stream;
            lastStreamAt = System.currentTimeMillis();
            try {
                Intent i = new Intent(this, PlayerActivity.class);
                i.putExtra(PlayerActivity.EXTRA_URL, stream);
                if (channelsJson != null) {
                    i.putExtra(PlayerActivity.EXTRA_LIST, channelsJson);
                    i.putExtra(PlayerActivity.EXTRA_INDEX, index);
                }
                startActivity(i);
            } catch (Exception e) {
                playerOpen = false;
                Toast.makeText(this, "Player failed to open: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void handleStream(String stream) {
        runOnUiThread(() -> {
            stopPreview();
            long now = System.currentTimeMillis();
            if (playerOpen) return;
            if (stream.equals(lastStream) && now - lastStreamAt < 5000) return;
            playerOpen = true;
            lastStream = stream;
            lastStreamAt = now;
            // Stop the web player so audio doesn't double up.
            web.evaluateJavascript(
                    "document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();v.removeAttribute('src');v.load();}catch(e){}})",
                    null);
            Intent i = new Intent(this, PlayerActivity.class);
            i.putExtra(PlayerActivity.EXTRA_URL, stream);
            Toast.makeText(this, "Opening player…", Toast.LENGTH_SHORT).show();
            try { startActivity(i); } catch (Exception e) {
                playerOpen = false;
                Toast.makeText(this, "Player failed to open: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private static Map<String, String> corsHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        return h;
    }

    // ---------- Live preview window ----------

    private androidx.media3.exoplayer.ExoPlayer previewPlayer;
    private androidx.media3.ui.PlayerView previewView;
    private String previewUrl;
    private boolean previewTriedTs;

    void showPreview(String url, float x, float y, float w, float h) {
        if (url == null || url.isEmpty() || isFinishing() || playerOpen) return;
        float d = getResources().getDisplayMetrics().density;
        int[] loc = new int[2];
        web.getLocationInWindow(loc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        if (previewView == null) {
            previewView = new androidx.media3.ui.PlayerView(this);
            previewView.setUseController(false);
            previewView.setShutterBackgroundColor(Color.TRANSPARENT);
            previewView.setBackgroundColor(Color.TRANSPARENT);
            previewView.setFocusable(false);
            root.addView(previewView, new FrameLayout.LayoutParams(1, 1));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w * d), Math.round(h * d));
        lp.leftMargin = Math.round(x * d) + loc[0] - rootLoc[0];
        lp.topMargin = Math.round(y * d) + loc[1] - rootLoc[1];
        previewView.setLayoutParams(lp);
        previewView.setVisibility(View.VISIBLE);

        if (url.equals(previewUrl) && previewPlayer != null) return;
        releasePreviewPlayer();
        previewUrl = url;
        previewTriedTs = false;
        startPreview(url);
    }

    private void startPreview(String url) {
        previewPlayer = new androidx.media3.exoplayer.ExoPlayer.Builder(this)
                .setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                        .setDataSourceFactory(new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                                .setAllowCrossProtocolRedirects(true)
                                .setConnectTimeoutMs(10000)
                                .setReadTimeoutMs(15000)))
                .build();
        previewView.setPlayer(previewPlayer);
        androidx.media3.common.MediaItem.Builder item =
                new androidx.media3.common.MediaItem.Builder().setUri(url);
        if (url.contains(".m3u8")) item.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
        previewPlayer.setMediaItem(item.build());
        previewPlayer.addListener(new androidx.media3.common.Player.Listener() {
            @Override
            public void onPlayerError(androidx.media3.common.PlaybackException error) {
                // Try the .ts version once, otherwise just leave the logo showing
                if (!previewTriedTs && url.contains("/live/") && url.contains(".m3u8")) {
                    previewTriedTs = true;
                    String ts = url.replace(".m3u8", ".ts");
                    root.post(() -> {
                        releasePreviewPlayer();
                        if (previewView != null) startPreview(ts);
                    });
                } else {
                    root.post(() -> {
                        releasePreviewPlayer();
                        if (previewView != null) previewView.setVisibility(View.GONE);
                    });
                }
            }
        });
        previewPlayer.setPlayWhenReady(true);
        previewPlayer.prepare();
    }

    private void releasePreviewPlayer() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        if (previewView != null) previewView.setPlayer(null);
    }

    void stopPreview() {
        releasePreviewPlayer();
        previewUrl = null;
        if (previewView != null) previewView.setVisibility(View.GONE);
    }

    /** JavaScript can call RebornNative.play(url) to open the native player. */
    public static class NativeBridge {
        private final MainActivity activity;
        NativeBridge(MainActivity a) { activity = a; }

        @JavascriptInterface
        public void play(String url) {
            String stream = StreamDetector.find(url);
            if (stream != null) activity.handleStream(stream);
        }

        /** Called by Reborn itself with the exact stream link: always open it. */
        @JavascriptInterface
        public void playDirect(String url) {
            if (url != null && !url.isEmpty()) activity.openPlayer(url);
        }

        /** Live TV: the stream plus the channel list it came from, for channel up/down. */
        @JavascriptInterface
        public void playChannels(String url, String channelsJson, int index) {
            if (url != null && !url.isEmpty()) activity.openPlayer(url, channelsJson, index);
        }

        /** Small live preview at a spot on the page (CSS pixels from the top-left of the page). */
        @JavascriptInterface
        public void preview(String url, float x, float y, float w, float h) {
            activity.runOnUiThread(() -> activity.showPreview(url, x, y, w, h));
        }

        @JavascriptInterface
        public void previewStop() {
            activity.runOnUiThread(activity::stopPreview);
        }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }
    }

    private void injectNav() {
        if (navScript != null) web.evaluateJavascript(navScript, null);
    }

    private void showOffline() {
        mainFrameFailed = true;
        spinner.setVisibility(View.GONE);
        web.setVisibility(View.INVISIBLE);
        offlineView.setVisibility(View.VISIBLE);
        offlineView.findViewWithTag("retry").requestFocus();
    }

    private LinearLayout buildOfflineView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.parseColor("#0B0F17"));
        box.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText(R.string.offline_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.offline_body);
        body.setTextColor(Color.parseColor("#9AA4B2"));
        body.setTextSize(20);
        body.setGravity(Gravity.CENTER);
        body.setPadding(0, 24, 0, 40);
        box.addView(body);

        Button retry = new Button(this);
        retry.setTag("retry");
        retry.setText(R.string.retry);
        retry.setTextSize(20);
        retry.setFocusable(true);
        retry.setOnClickListener(v -> loadHome());
        box.addView(retry);
        return box;
    }

    private void exitFullscreen() {
        if (customView == null) return;
        fullscreenHolder.removeView(customView);
        fullscreenHolder.setVisibility(View.GONE);
        customView = null;
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        web.setVisibility(View.VISIBLE);
        web.requestFocus();
        hideSystemUi();
    }

    // ---------- Remote control ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            String media = null;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: media = "playpause"; break;
                case KeyEvent.KEYCODE_MEDIA_PLAY:       media = "play"; break;
                case KeyEvent.KEYCODE_MEDIA_PAUSE:      media = "pause"; break;
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: media = "ff"; break;
                case KeyEvent.KEYCODE_MEDIA_REWIND:     media = "rw"; break;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                web.evaluateJavascript("window.__rebornFav && window.__rebornFav()", null);
                return true;
            }
            if (media != null) {
                web.evaluateJavascript("window.__rebornMedia && window.__rebornMedia('" + media + "')", null);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (offlineView.getVisibility() == View.VISIBLE) { finish(); return; }
        if (customView != null) { exitFullscreen(); return; }

        // Give the web app first chance (close player / modal), then browser history, then exit.
        web.evaluateJavascript("window.__rebornBack ? window.__rebornBack() : false", value -> {
            if ("true".equals(value)) return;
            if (web.canGoBack()) { web.goBack(); return; }
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
        web.onPause();   // stops audio when you press Home
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playerOpen) {
            // Back from the native player: close Reborn's own player screen too.
            playerOpen = false;
            lastStreamAt = System.currentTimeMillis();
            web.evaluateJavascript("window.__rebornBack && window.__rebornBack()", null);
        }
        web.onResume();
        web.evaluateJavascript("window.__rebornPreviewRefresh && window.__rebornPreviewRefresh()", null);
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        if (web != null) {
            web.stopLoading();
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    // ---------- Helpers ----------

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return true;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private String loadAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
