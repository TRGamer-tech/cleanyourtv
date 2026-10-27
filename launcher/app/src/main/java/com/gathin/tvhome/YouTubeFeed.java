package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The newest upload of each of your subscriptions, from the YouTube Data API (it has no watch history). The refresh token
 * and the OAuth client come from a computer (FeedSecrets). Opens in SmartTube.
 */
final class YouTubeFeed implements Feed {
    static final String PACKAGE = "org.smarttube.stable";
    private static final int LIMIT = 4;
    private static final int CHANNELS = 10;
    private static final String API = "https://www.googleapis.com/youtube/v3/";

    private final Context context;
    private String accessToken;
    private long expiresAt;

    YouTubeFeed(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    public Result fetch() throws Exception {
        SharedPreferences prefs = FeedSecrets.prefs(context);
        if (prefs.getString("youtube_refresh_token", null) == null) {
            return Result.message(context.getString(R.string.feed_not_linked, "YouTube"), 0);
        }
        String token = token(prefs);
        JSONArray subs = new JSONObject(FeedHttp.get(API + "subscriptions?part=snippet&mine=true&order=relevance&maxResults="
                + CHANNELS, token)).optJSONArray("items");
        final List<String[]> videos = new ArrayList<>();   // {published, title, channel, id, image}
        for (int i = 0; subs != null && i < subs.length(); i++) {
            String channelId = subs.getJSONObject(i).getJSONObject("snippet").getJSONObject("resourceId").optString("channelId");
            if (!channelId.startsWith("UC")) continue;
            try {
                JSONArray items = new JSONObject(FeedHttp.get(API + "playlistItems?part=snippet&maxResults=1&playlistId=UU"
                        + channelId.substring(2), token)).optJSONArray("items");
                if (items == null || items.length() == 0) continue;
                JSONObject snippet = items.getJSONObject(0).getJSONObject("snippet");
                String id = snippet.getJSONObject("resourceId").optString("videoId");
                JSONObject thumbs = snippet.optJSONObject("thumbnails");
                JSONObject thumb = thumbs == null ? null : thumbs.optJSONObject("medium");
                videos.add(new String[]{snippet.optString("publishedAt"), snippet.optString("title"),
                        snippet.optString("videoOwnerChannelTitle", snippet.optString("channelTitle")), id,
                        thumb == null ? null : thumb.optString("url", null)});
            } catch (java.io.IOException skipChannel) {
                // a channel with no uploads playlist or no access: leave it out
            }
        }
        Collections.sort(videos, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return b[0].compareTo(a[0]);   // ISO timestamps sort as text
            }
        });
        List<Item> out = new ArrayList<>();
        for (String[] v : videos) {
            if (out.size() >= LIMIT) break;
            out.add(new Item(v[1], v[2], v[4], "https://www.youtube.com/watch?v=" + v[3]));
        }
        return Result.items(out);
    }

    private String token(SharedPreferences prefs) throws Exception {
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60000) return accessToken;
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", prefs.getString("youtube_refresh_token", ""));
        form.put("client_id", prefs.getString("youtube_client_id", ""));
        form.put("client_secret", prefs.getString("youtube_client_secret", ""));
        JSONObject json = new JSONObject(FeedHttp.postForm("https://oauth2.googleapis.com/token", form));
        accessToken = json.getString("access_token");
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000;
        return accessToken;
    }
}
