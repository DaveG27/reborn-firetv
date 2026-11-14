package app.reborn.iptv;

import java.net.URI;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a URL the web app is loading is a video stream.
 * Handles direct Xtream links and links wrapped in the app's own proxy (?url=...).
 * Returns the real stream URL, or null if it isn't a stream.
 */
public final class StreamDetector {

    private StreamDetector() {}

    // Xtream style: /live/USER/PASS/123.ts, /movie/USER/PASS/456.mkv, /series/..., /timeshift/...
    private static final Pattern XTREAM =
            Pattern.compile("/(live|movie|series|timeshift)/[^/]+/[^/]+/[^/?#]+", Pattern.CASE_INSENSITIVE);

    // Media file extensions (first segment of an HLS stream is the .m3u8, which we catch first)
    private static final Pattern MEDIA_EXT =
            Pattern.compile("\\.(m3u8|ts|mkv|mp4|m4v|avi|mov|flv|webm|mpd)(\\?|#|$)", Pattern.CASE_INSENSITIVE);

    // Query parameters that proxies commonly use to carry the real URL
    private static final Pattern WRAPPED =
            Pattern.compile("[?&](url|u|src|stream|target|link)=([^&#]+)", Pattern.CASE_INSENSITIVE);

    // Things that are never streams
    private static final Pattern IGNORE =
            Pattern.compile("(player_api\\.php|xmltv\\.php|get\\.php|panel_api\\.php|\\.(png|jpe?g|gif|webp|svg|ico|css|js|json|woff2?|ttf|map)(\\?|#|$))",
                    Pattern.CASE_INSENSITIVE);

    public static String find(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;

        // Unwrap proxied links first (possibly more than once).
        for (int i = 0; i < 3; i++) {
            Matcher w = WRAPPED.matcher(url);
            if (!w.find()) break;
            try {
                String inner = URLDecoder.decode(w.group(2), "UTF-8");
                if (inner.startsWith("http://") || inner.startsWith("https://")) {
                    url = inner;
                } else break;
            } catch (Exception e) {
                break;
            }
        }

        String path;
        try {
            URI u = URI.create(url.replace(" ", "%20"));
            path = (u.getRawPath() == null ? "" : u.getRawPath())
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } catch (Exception e) {
            path = url;
        }

        if (IGNORE.matcher(path).find()) return null;

        String lower = path.toLowerCase(Locale.ROOT);
        // HLS segments / keys inside a playlist: not a new stream.
        if (lower.contains("/hls/") && lower.contains(".ts")) return null;

        if (XTREAM.matcher(path).find()) return url;
        if (MEDIA_EXT.matcher(path).find()) {
            // .ts on its own is usually an HLS segment; only treat as a stream if it looks like Xtream live.
            if (lower.matches(".*\\.ts(\\?.*)?$") && !lower.contains("/live/")) return null;
            return url;
        }
        return null;
    }
}
