package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Sign-in tokens for the feeds that cannot sign in on the TV (Spotify, YouTube). They are made on a computer and pushed as
 * feeds.json into this app's external files folder, the same place themes go. The file is read into the launcher's private
 * preferences whenever it is newer than the last import; remove it from the TV afterwards.
 */
final class FeedSecrets {
    private FeedSecrets() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("feeds", Context.MODE_PRIVATE);
    }

    /** Reads feeds.json ({"spotify": {"client_id": ..., "refresh_token": ...}, ...}) into the preferences as "spotify_client_id" and so on. */
    static void importFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        File file = new File(dir, "feeds.json");
        SharedPreferences prefs = prefs(context);
        if (!file.isFile() || file.lastModified() <= prefs.getLong("feeds_imported", 0)) return;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
            JSONObject root = new JSONObject(buf.toString("UTF-8"));
            SharedPreferences.Editor edit = prefs.edit();
            for (Iterator<String> services = root.keys(); services.hasNext(); ) {
                String service = services.next();
                JSONObject values = root.optJSONObject(service);
                if (values == null) continue;
                for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    edit.putString(service + "_" + key, values.getString(key));
                }
            }
            edit.putLong("feeds_imported", file.lastModified()).apply();
            file.delete();   // may not be allowed for a file the shell wrote; the timestamp stops a second import
        } catch (IOException | org.json.JSONException ignored) {
            // a bad file leaves the feeds unlinked
        }
    }
}
