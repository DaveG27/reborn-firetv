package app.reborn.iptv;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.content.SharedPreferences;
import java.util.Locale;
import androidx.media3.common.TrackSelectionParameters;

import androidx.media3.datasource.HttpDataSource;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

/**
 * Native full-screen player. Plays HLS (.m3u8), MPEG-TS (.ts), MKV, MP4 etc.
 * Dolby audio is passed through to the TV/soundbar when the device supports it.
 */
public class PlayerActivity extends Activity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_LIST = "channels";   // JSON [{name,url},...] for channel up/down
    public static final String EXTRA_INDEX = "index";

    private org.json.JSONArray channels;
    private int chIndex = -1;
    private TextView banner;
    private View errorView;

    private ExoPlayer player;
    private PlayerView view;
    private String url;
    private int retries = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        url = getIntent().getStringExtra(EXTRA_URL);
        if (url == null) { finish(); return; }
        try {
            String list = getIntent().getStringExtra(EXTRA_LIST);
            if (list != null) channels = new org.json.JSONArray(list);
            chIndex = getIntent().getIntExtra(EXTRA_INDEX, -1);
        } catch (Exception e) {
            channels = null;
        }

        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setControllerShowTimeoutMs(3000);
        view.setControllerAutoShow(false);
        view.setShowSubtitleButton(true);
        setContentView(view);

        // Channel number + name banner (top-left), shown briefly after switching
        banner = new TextView(this);
        banner.setTextColor(Color.WHITE);
        banner.setTextSize(22);
        banner.setPadding(28, 16, 28, 16);
        banner.setBackgroundColor(Color.parseColor("#CC111316"));
        banner.setVisibility(View.GONE);
        android.widget.FrameLayout.LayoutParams bp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        bp.setMargins(48, 40, 0, 0);
        ((ViewGroup) view.getParent()).addView(banner, bp);

        hideSystemUi();
        start();
        if (channels != null && chIndex >= 0) showBanner();
    }

    private void start() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);

        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
                .build();
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        applySubtitles(subsOn());
        view.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(url));
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8") || lower.contains("output=m3u8") || lower.contains("output=hls")) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8);
        }
        player.setMediaItem(item.build());

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // Live streams drop now and then – retry a few times before giving up.
                if (retries < 3) {
                    retries++;
                    player.seekToDefaultPosition();
                    player.prepare();
                    return;
                }
                // Live channel failed as .m3u8: many providers only serve .ts – try that once.
                String alt = fallbackUrl(url);
                if (alt != null) {
                    url = alt;
                    retries = 0;
                    player.release();
                    start();
                    return;
                }
                showError(error);
            }

            // Remember if subtitles were switched on/off with the on-screen CC button.
            @Override
            public void onTrackSelectionParametersChanged(TrackSelectionParameters p) {
                boolean on = !p.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
                if (on != subsOn()) prefs().edit().putBoolean("subs_on", on).apply();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) scheduleHide();
                else view.showController();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) retries = 0;
                if (state == Player.STATE_ENDED) finish();
            }
        });

        player.setPlayWhenReady(true);
        player.prepare();
    }

    private final android.os.Handler hideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideRunnable = () -> {
        if (player != null && player.isPlaying() && view != null) view.hideController();
    };

    /** Hide the on-screen controls a few seconds after the last button press while playing. */
    private void scheduleHide() {
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.postDelayed(hideRunnable, 3500);
    }

    // ---------- Subtitles ----------

    private SharedPreferences prefs() {
        return getSharedPreferences("player", MODE_PRIVATE);
    }

    private boolean subsOn() {
        return prefs().getBoolean("subs_on", false);
    }

    private void applySubtitles(boolean on) {
        String lang = Locale.getDefault().getLanguage();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on)
                .setPreferredTextLanguages(lang, "en")
                .setSelectUndeterminedTextLanguage(true)
                .setIgnoredTextSelectionFlags(0)
                .build());
    }

    private void toggleSubtitles() {
        if (!player.getCurrentTracks().containsType(C.TRACK_TYPE_TEXT)) {
            Toast.makeText(this, "No subtitles available for this", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = !subsOn();
        prefs().edit().putBoolean("subs_on", on).apply();
        applySubtitles(on);
        Toast.makeText(this, on ? "Subtitles on" : "Subtitles off", Toast.LENGTH_SHORT).show();
    }

    /** Stays on screen with the exact reason so it can be read from the sofa. Back closes it. */
    private void showError(PlaybackException error) {
        String detail = error.getErrorCodeName();
        Throwable c = error.getCause();
        while (c != null) {
            if (c instanceof HttpDataSource.InvalidResponseCodeException) {
                int code = ((HttpDataSource.InvalidResponseCodeException) c).responseCode;
                detail = "Provider refused the stream (HTTP " + code + ")";
                break;
            }
            if (c instanceof HttpDataSource.HttpDataSourceException && c.getCause() != null) {
                detail = "Network: " + c.getCause().getClass().getSimpleName() + " " + c.getCause().getMessage();
            }
            c = c.getCause();
        }
        String host = Uri.parse(url).getHost();
        String type = url.contains(".m3u8") ? "m3u8" : url.contains(".ts") ? "ts" : "file";

        TextView t = new TextView(this);
        t.setText("Couldn't play this stream\n\n" + detail + "\n\nServer: " + host + "   Type: " + type
                + "\n\nPress Back to return");
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Color.parseColor("#E6000000"));
        if (errorView != null) ((ViewGroup) errorView.getParent()).removeView(errorView);
        errorView = t;
        if (channels != null) t.setText(t.getText() + "\n\nUp/Down to change channel");
        ((ViewGroup) view.getParent()).addView(t, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (player != null) player.stop();
    }

    // ---------- Channel up/down ----------

    private final Runnable hideBanner = () -> banner.setVisibility(View.GONE);

    private void showBanner() {
        try {
            org.json.JSONObject ch = channels.getJSONObject(chIndex);
            banner.setText(ch.optInt("num", chIndex + 1) + "   " + ch.optString("name", ""));
            banner.setVisibility(View.VISIBLE);
            hideHandler.removeCallbacks(hideBanner);
            hideHandler.postDelayed(hideBanner, 3000);
        } catch (Exception ignored) { }
    }

    private boolean zap(int step) {
        if (channels == null || channels.length() < 2 || chIndex < 0) return false;
        int n = channels.length();
        chIndex = ((chIndex + step) % n + n) % n;
        try {
            url = channels.getJSONObject(chIndex).getString("url");
        } catch (Exception e) {
            return false;
        }
        if (errorView != null) {
            ((ViewGroup) errorView.getParent()).removeView(errorView);
            errorView = null;
        }
        retries = 0;
        if (player != null) player.release();
        start();
        showBanner();
        return true;
    }

    /** Next format to try after a stream fails, or null when there's nothing left. */
    private static String fallbackUrl(String u) {
        if (u.contains("/live/") && u.contains(".m3u8")) return u.replace(".m3u8", ".ts");
        if ((u.contains("/movie/") || u.contains("/series/")) && u.matches(".*\\.m3u8(\\?.*)?$")) {
            return u.replaceAll("\\.m3u8", ".mp4");
        }
        return null;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && player != null) {
            scheduleHide();
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                    if (zap(+1)) return true;
                    break;
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                    if (zap(-1)) return true;
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                    // Up = channel above in the list, like moving up the channel list
                    if ((errorView != null || !view.isControllerFullyVisible()) && zap(-1)) return true;
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if ((errorView != null || !view.isControllerFullyVisible()) && zap(+1)) return true;
                    break;
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_CAPTIONS:
                    toggleSubtitles();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (!view.isControllerFullyVisible()) {
                        player.setPlayWhenReady(!player.getPlayWhenReady());
                        view.showController();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (!view.isControllerFullyVisible() && player.isCurrentMediaItemSeekable()) {
                        player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                        view.showController();
                        return true;
                    }
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (!view.isControllerFullyVisible() && player.isCurrentMediaItemSeekable()) {
                        player.seekTo(player.getCurrentPosition() + 10000);
                        view.showController();
                        return true;
                    }
                    break;
            }
        }
        return view.dispatchKeyEvent(event) || super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (player != null) player.play();
        hideSystemUi();
    }

    @Override
    protected void onDestroy() {
        hideHandler.removeCallbacks(hideRunnable);
        hideHandler.removeCallbacks(hideBanner);
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
