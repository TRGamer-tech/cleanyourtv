package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What was last watched on the user's Jellyfin server. Signs in with Quick Connect: the TV shows a code, the user
 * approves it in a Jellyfin app, and the access token is kept in this app's private preferences.
 */
final class JellyfinFeed implements Feed {
    static final String PACKAGE = "org.jellyfin.androidtv";
    private static final int LIMIT = 4;

    private final Context context;
    private final String server;
    private final SharedPreferences prefs;
    private String secret;
    private String code;

    JellyfinFeed(Context context, String server) {
        this.context = context.getApplicationContext();
        this.server = server.endsWith("/") ? server.substring(0, server.length() - 1) : server;
        this.prefs = this.context.getSharedPreferences("feeds", Context.MODE_PRIVATE);
    }

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    public Result fetch() throws Exception {
        String token = prefs.getString("jellyfin_token", null);
        if (token == null) return signIn();
        try {
            return Result.items(load(token));
        } catch (Unauthorized e) {
            prefs.edit().remove("jellyfin_token").remove("jellyfin_user").apply();
            return signIn();
        }
    }

    private Result signIn() throws Exception {
        if (secret == null) {
            JSONObject start = new JSONObject(request("POST", "/QuickConnect/Initiate", null, null));
            secret = start.getString("Secret");
            code = start.getString("Code");
        } else {
            JSONObject state;
            try {
                state = new JSONObject(request("GET", "/QuickConnect/Connect?secret=" + secret, null, null));
            } catch (IOException e) {
                secret = null;   // expired: ask for a new code on the next round
                return Result.message(context.getString(R.string.jellyfin_link, "…"), 1000);
            }
            if (state.optBoolean("Authenticated")) {
                JSONObject body = new JSONObject().put("Secret", secret);
                JSONObject auth = new JSONObject(request("POST", "/Users/AuthenticateWithQuickConnect", null, body.toString()));
                prefs.edit().putString("jellyfin_token", auth.getString("AccessToken"))
                        .putString("jellyfin_user", auth.getJSONObject("User").getString("Id")).apply();
                secret = null;
                code = null;
                return fetch();
            }
        }
        return Result.message(context.getString(R.string.jellyfin_link, code), 4000);
    }

    private List<Item> load(String token) throws Exception {
        String user = prefs.getString("jellyfin_user", "");
        List<JSONObject> found = new ArrayList<>();
        found.addAll(items(request("GET", "/UserItems/Resume?userId=" + user + "&limit=" + LIMIT
                + "&mediaTypes=Video&enableImageTypes=Thumb,Backdrop,Primary", token, null)));
        found.addAll(items(request("GET", "/Items?userId=" + user + "&recursive=true&isPlayed=true"
                + "&includeItemTypes=Movie,Episode&sortBy=DatePlayed&sortOrder=Descending&limit=" + (LIMIT * 4)
                + "&enableImageTypes=Thumb,Backdrop,Primary", token, null)));
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JSONObject o : found) {
            boolean episode = "Episode".equals(o.optString("Type"));
            String key = episode ? o.optString("SeriesId", o.optString("Id")) : o.optString("Id");
            if (!seen.add(key) || out.size() >= LIMIT) continue;
            String title = episode ? o.optString("SeriesName", o.optString("Name")) : o.optString("Name");
            String subtitle = episode
                    ? "S" + o.optInt("ParentIndexNumber") + "E" + o.optInt("IndexNumber") + " · " + o.optString("Name")
                    : o.optString("ProductionYear", "");
            out.add(new Item(title, subtitle, imageUrl(o), null));
        }
        return out;
    }

    private static List<JSONObject> items(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONArray array = root.optJSONArray("Items");
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; array != null && i < array.length(); i++) list.add(array.getJSONObject(i));
        return list;
    }

    private String imageUrl(JSONObject o) {
        String id = o.optString("Id");
        JSONObject tags = o.optJSONObject("ImageTags");
        JSONArray backdrops = o.optJSONArray("BackdropImageTags");
        String kind;
        if (tags != null && tags.has("Thumb")) kind = "Thumb";
        else if (backdrops != null && backdrops.length() > 0) kind = "Backdrop";
        else kind = "Primary";
        return server + "/Items/" + id + "/Images/" + kind + "?maxWidth=480&quality=80";
    }

    private String deviceId() {
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String request(String method, String path, String token, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            String auth = "MediaBrowser Client=\"CleanYourTV\", Device=\"" + Build.MODEL + "\", DeviceId=\"" + deviceId()
                    + "\", Version=\"1.0\"" + (token == null ? "" : ", Token=\"" + token + "\"");
            c.setRequestProperty("Authorization", auth);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            } else if ("POST".equals(method)) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(0);
            }
            int status = c.getResponseCode();
            if (status == 401 || status == 403) throw new Unauthorized();
            if (status >= 400) throw new IOException("HTTP " + status);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                return buf.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static final class Unauthorized extends IOException {
    }
}
