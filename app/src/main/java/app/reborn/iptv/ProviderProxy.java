package app.reborn.iptv;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Does Reborn's provider lookups (login, channel/film/series lists, guide) straight from the box,
 * instead of through the website's proxy on Vercel.
 *
 * Reborn asks for  /api/proxy?url=SERVER&username=U&password=P&action=...
 * and we answer exactly like api/proxy.js would, so the website needs no changes.
 * Browsers can't do this (they block cross-site requests); the app can.
 */
final class ProviderProxy {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private ProviderProxy() {}

    /** Returns a response if this is a Reborn proxy call we handled, otherwise null. */
    static WebResourceResponse handle(WebResourceRequest req, String appHost) {
        if (!"GET".equalsIgnoreCase(req.getMethod())) return null;
        Uri u = req.getUrl();
        if (u == null || !"/api/proxy".equals(u.getPath())) return null;
        if (appHost != null && !appHost.equalsIgnoreCase(u.getHost())) return null;

        String server = u.getQueryParameter("url");
        String user = u.getQueryParameter("username");
        String pass = u.getQueryParameter("password");
        if (server == null || user == null || pass == null) {
            return json(400, "{\"error\":\"Missing required parameters\"}");
        }

        // Same URL the Vercel proxy builds
        StringBuilder target = new StringBuilder(server.replaceAll("/+$", ""))
                .append("/player_api.php?username=").append(enc(user))
                .append("&password=").append(enc(pass));
        for (String name : u.getQueryParameterNames()) {
            if (name.equals("url") || name.equals("username") || name.equals("password")) continue;
            for (String v : u.getQueryParameters(name)) {
                target.append('&').append(enc(name)).append('=').append(enc(v));
            }
        }

        try {
            Result r = get(target.toString());
            if (looksLikeJson(r.body)) return json(r.status, r.body);
            String raw = new String(r.body, 0, Math.min(r.body.length, 100), StandardCharsets.UTF_8)
                    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
            return json(r.status == 200 ? 502 : r.status,
                    "{\"error\":\"Provider returned non-JSON response (" + r.status + ")\",\"raw\":\"" + raw + "\"}");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage()).replace("\\", "\\\\").replace("\"", "\\\"");
            return json(502, "{\"error\":\"Failed to connect to provider\",\"message\":\"" + msg + "\"}");
        }
    }

    private static final class Result {
        int status;
        byte[] body;
    }

    /** GET with redirects followed (including http <-> https, which Android doesn't do by itself). */
    private static Result get(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", UA);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            c.disconnect();
            Result r = new Result();
            r.status = code;
            r.body = out.toByteArray();
            return r;
        }
        throw new Exception("Too many redirects");
    }

    private static boolean looksLikeJson(byte[] b) {
        for (byte x : b) {
            if (x == ' ' || x == '\n' || x == '\r' || x == '\t') continue;
            // allow a UTF-8 byte-order mark
            if ((x & 0xFF) == 0xEF || (x & 0xFF) == 0xBB || (x & 0xFF) == 0xBF) continue;
            return x == '{' || x == '[' || x == '"' || x == 'n' || x == 't' || x == 'f'
                    || x == '-' || (x >= '0' && x <= '9');
        }
        return false;
    }

    private static WebResourceResponse json(int status, String body) {
        return json(status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static WebResourceResponse json(int status, byte[] body) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 502 ? "Bad Gateway" : "Status";
        return new WebResourceResponse("application/json", "utf-8", status, reason, h,
                new ByteArrayInputStream(body));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
