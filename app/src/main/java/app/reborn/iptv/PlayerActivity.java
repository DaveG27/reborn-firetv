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

        view = new PlayerView(this);
        view.setKeepScreenOn(true);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setControllerShowTimeoutMs(3000);
        setContentView(view);
        hideSystemUi();
        start();
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

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) retries = 0;
                if (state == Player.STATE_ENDED) finish();
            }
        });

        player.setPlayWhenReady(true);
        player.prepare();
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
        ((ViewGroup) view.getParent()).addView(t, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (player != null) player.stop();
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
            switch (event.getKeyCode()) {
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
