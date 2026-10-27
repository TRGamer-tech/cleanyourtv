package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recently played tracks and your playlists, from Spotify's Web API, with a refresh token made on a computer (FeedSecrets). */
final class SpotifyFeed implements Feed {
    static final String PACKAGE = "com.spotify.tv.android";
    private static final int LIMIT = 4;
    /** Spotify's DJ is an algorithmic playlist with this fixed id; the API will not name it. */
    /** Liked Songs, as the web link Spotify's TV app opens and plays. */
    private static final String LIKED = "https://open.spotify.com/collection/tracks";
    private static final String DJ_PLAYLIST = "37i9dQZF1EYkqdzj48dyYq";

    private final Context context;
    private String accessToken;
    private long expiresAt;

    SpotifyFeed(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String packageName() {
        return PACKAGE;
    }

    @Override
    public Result fetch() throws Exception {
        SharedPreferences prefs = FeedSecrets.prefs(context);
        if (prefs.getString("spotify_refresh_token", null) == null) {
            return Result.message(context.getString(R.string.feed_not_linked, "Spotify"), 0);
        }
        String token = token(prefs);
        JSONArray recent = new JSONObject(FeedHttp.get("https://api.spotify.com/v1/me/player/recently-played?limit=50", token))
                .optJSONArray("items");
        // What was last played from, newest first: a playlist (the DJ included), Liked Songs, an album or an artist.
        Map<String, JSONObject> contexts = new LinkedHashMap<>();
        for (int i = 0; recent != null && i < recent.length() && contexts.size() < LIMIT; i++) {
            JSONObject played = recent.getJSONObject(i);
            JSONObject ctx = played.optJSONObject("context");
            if (ctx == null || ctx.isNull("uri")) continue;
            String uri = ctx.getString("uri");
            if (!contexts.containsKey(uri)) contexts.put(uri, played.getJSONObject("track"));
        }
        List<Item> out = new ArrayList<>();
        for (Map.Entry<String, JSONObject> entry : contexts.entrySet()) {
            Item item = contextItem(entry.getKey(), entry.getValue(), token);
            if (item != null) out.add(item);
        }
        if (out.size() < LIMIT) {   // fewer than four contexts lately: top up with your own playlists
            JSONArray playlists = new JSONObject(FeedHttp.get("https://api.spotify.com/v1/me/playlists?limit=10", token)).optJSONArray("items");
            for (int i = 0; playlists != null && i < playlists.length() && out.size() < LIMIT; i++) {
                JSONObject list = playlists.getJSONObject(i);
                String uri = list.optString("uri", null);
                if (list.isNull("name") || contexts.containsKey(uri)) continue;
                out.add(new Item(list.optString("name"), context.getString(R.string.feed_playlist), image(list.optJSONArray("images")), web(uri)));
            }
        }
        // Liked Songs never turns up in the recently played data, so it is always here, second after the newest context.
        String likedName = context.getString(R.string.feed_liked);
        boolean hasLiked = false;
        for (Item item : out) if (likedName.equals(item.title)) hasLiked = true;
        if (!hasLiked) {
            String cover = null;
            try {
                JSONArray liked = new JSONObject(FeedHttp.get("https://api.spotify.com/v1/me/tracks?limit=1", token)).optJSONArray("items");
                if (liked != null && liked.length() > 0) {
                    cover = image(liked.getJSONObject(0).getJSONObject("track").getJSONObject("album").optJSONArray("images"));
                }
            } catch (Exception ignored) {
                // no library permission or no network for this one: the card just has no cover
            }
            out.add(Math.min(1, out.size()), new Item(likedName, context.getString(R.string.feed_playlist), cover, LIKED));
            while (out.size() > LIMIT) out.remove(out.size() - 1);
        }
        return Result.items(out);
    }

    /** One card for a context. The track is the newest one played from it, whose cover stands in when the context has none. */
    private Item contextItem(String uri, JSONObject track, String token) {
        String[] part = uri.split(":");
        JSONObject album = track.optJSONObject("album");
        String cover = album == null ? null : image(album.optJSONArray("images"));
        String kind = part.length > 1 ? part[1] : "";
        if (part.length >= 4 && "collection".equals(part[part.length - 1])) {
            return new Item(context.getString(R.string.feed_liked), context.getString(R.string.feed_playlist), cover, LIKED);
        }
        if (part.length >= 5 && "playlist".equals(part[3])) kind = "playlist";
        String id = part.length > 0 ? part[part.length - 1] : "";
        try {
            if ("playlist".equals(kind)) {
                String name;
                String art = cover;
                try {
                    JSONObject list = new JSONObject(FeedHttp.get("https://api.spotify.com/v1/playlists/" + id + "?fields=name,images", token));
                    name = list.optString("name");
                    String own = image(list.optJSONArray("images"));
                    if (own != null) art = own;
                } catch (java.io.IOException e) {
                    name = DJ_PLAYLIST.equals(id) ? "DJ" : context.getString(R.string.feed_playlist);   // Spotify's own lists are not open to the API
                }
                return new Item(name, context.getString(R.string.feed_playlist), art, web(uri));
            }
            if ("album".equals(kind) && album != null) {
                return new Item(album.optString("name"), context.getString(R.string.feed_album), cover, web(uri));
            }
            if ("artist".equals(kind)) {
                JSONObject artist = new JSONObject(FeedHttp.get("https://api.spotify.com/v1/artists/" + id, token));
                String art = image(artist.optJSONArray("images"));
                return new Item(artist.optString("name"), context.getString(R.string.feed_artist), art != null ? art : cover, web(uri));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private String token(SharedPreferences prefs) throws Exception {
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60000) return accessToken;
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", prefs.getString("spotify_refresh_token", ""));
        form.put("client_id", prefs.getString("spotify_client_id", ""));
        JSONObject json = new JSONObject(FeedHttp.postForm("https://accounts.spotify.com/api/token", form));
        accessToken = json.getString("access_token");
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000;
        if (json.has("refresh_token")) prefs.edit().putString("spotify_refresh_token", json.getString("refresh_token")).apply();
        return accessToken;
    }

    /**
     * spotify:playlist:ID as https://open.spotify.com/playlist/ID. Spotify's TV app opens and plays the web form, and only
     * lands on its home screen for the spotify: form.
     */
    private static String web(String uri) {
        if (uri == null) return null;
        String[] part = uri.split(":");
        if (part.length >= 3 && "spotify".equals(part[0]) && !"user".equals(part[1])) return "https://open.spotify.com/" + part[1] + "/" + part[2];
        if (part.length >= 5 && "user".equals(part[1]) && "playlist".equals(part[3])) return "https://open.spotify.com/playlist/" + part[4];
        if (part.length >= 4 && "user".equals(part[1]) && "collection".equals(part[3])) return LIKED;
        return null;
    }

    private static String artist(JSONObject track) {
        JSONArray artists = track.optJSONArray("artists");
        return artists != null && artists.length() > 0 ? artists.optJSONObject(0).optString("name") : "";
    }

    /** A middle-sized picture (about 300 px) when there is one. */
    private static String image(JSONArray images) {
        if (images == null || images.length() == 0) return null;
        return images.optJSONObject(images.length() > 1 ? 1 : 0).optString("url", null);
    }
}
