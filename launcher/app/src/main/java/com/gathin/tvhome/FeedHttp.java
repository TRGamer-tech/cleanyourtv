package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

/** The little HTTP the feeds need: a GET with a bearer token, and a form POST for token refreshes. */
final class FeedHttp {
    private FeedHttp() {}

    static String get(String url, String bearer) throws IOException {
        return call("GET", url, bearer, null);
    }

    static String postForm(String url, Map<String, String> form) throws IOException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return call("POST", url, null, body.toString());
    }

    private static String call(String method, String url, String bearer, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body.getBytes("UTF-8"));
                }
            }
            int status = c.getResponseCode();
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
}
