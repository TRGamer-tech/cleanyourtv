package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.KeyEvent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The row of recent titles beside the focused dock app's name. Only apps with a Feed get one; the data is fetched
 * on a worker thread when the app takes focus and kept for a few minutes. The cards are for looking at: they take no focus.
 */
final class FeedRow {
    private static final long FRESH_MS = 5 * 60 * 1000L;

    /** Where focus goes when the cards are left with Up or Down. */
    interface Navigator {
        void down();

        void up();
    }

    private final Context context;
    private final LinearLayout row;
    private final Navigator navigator;
    private final float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<String, Feed> feeds = new HashMap<>();
    private final Map<String, Feed.Result> cache = new HashMap<>();
    private final Map<String, Long> cachedAt = new HashMap<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private String current = "";
    private Runnable retry;

    FeedRow(Context context, LinearLayout row, Navigator navigator) {
        this.context = context;
        this.row = row;
        this.navigator = navigator;
        this.density = context.getResources().getDisplayMetrics().density;
        // The focused card grows a little past its bounds: headroom above it, and no clipping up to the screen.
        if (row != null) {
            row.setPadding(0, dp(8), 0, dp(6));
            row.setClipToPadding(false);
            for (View v = row; v instanceof android.view.ViewGroup; v = (View) v.getParent()) {
                ((android.view.ViewGroup) v).setClipChildren(false);
                if (v.getId() == R.id.hero) {
                    if (v.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) v.getParent()).setClipChildren(false);
                    break;
                }
                if (!(v.getParent() instanceof View)) break;
            }
        }
        FeedSecrets.importFile(context);
        feeds.put(SpotifyFeed.PACKAGE, new SpotifyFeed(context));
        feeds.put(YouTubeFeed.PACKAGE, new YouTubeFeed(context));
        // The server comes from feeds.json ({"jellyfin": {"server": "http://host:8096"}}) or, failing that, res/values/feeds.xml.
        String jellyfin = FeedSecrets.prefs(context).getString("jellyfin_server", context.getString(R.string.jellyfin_server)).trim();
        if (!jellyfin.isEmpty()) feeds.put(JellyfinFeed.PACKAGE, new JellyfinFeed(context, jellyfin));
    }

    /** The dock app that now has focus. */
    void bind(String packageName) {
        if (row == null) return;   // a layout without the row (Hebrew)
        current = packageName;
        cancelRetry();
        Feed feed = feeds.get(packageName);
        if (feed == null) {
            row.removeAllViews();
            row.setVisibility(View.GONE);
            return;
        }
        Feed.Result cached = cache.get(packageName);
        Long at = cachedAt.get(packageName);
        row.setVisibility(View.VISIBLE);
        if (cached != null) render(cached);
        else row.removeAllViews();
        boolean fresh = cached != null && cached.message == null && at != null
                && System.currentTimeMillis() - at < FRESH_MS;
        if (!fresh) fetch(feed);
    }

    void stop() {
        cancelRetry();
    }

    /** Up from the dock: focus the first card. False when this app has no cards on screen. */
    boolean focusFirst() {
        if (row == null || row.getVisibility() != View.VISIBLE || row.getChildCount() == 0) return false;
        View first = row.getChildAt(0);
        return first.isFocusable() && first.requestFocus();
    }

    private void cancelRetry() {
        if (retry != null) main.removeCallbacks(retry);
        retry = null;
    }

    private void fetch(final Feed feed) {
        io.execute(new Runnable() {
            @Override
            public void run() {
                Feed.Result result;
                try {
                    result = feed.fetch();
                } catch (Exception e) {
                    result = Feed.Result.message(context.getString(R.string.feed_error), 60000);
                }
                final Feed.Result done = result;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cache.put(feed.packageName(), done);
                        cachedAt.put(feed.packageName(), System.currentTimeMillis());
                        if (!feed.packageName().equals(current)) return;
                        render(done);
                        if (done.retryMs > 0) {
                            retry = new Runnable() {
                                @Override
                                public void run() {
                                    if (feed.packageName().equals(current)) fetch(feed);
                                }
                            };
                            main.postDelayed(retry, done.retryMs);
                        }
                    }
                });
            }
        });
    }

    private void render(Feed.Result result) {
        row.removeAllViews();
        if (result.message != null) {
            TextView text = new TextView(context);
            text.setText(result.message);
            text.setTextColor(0xCCFFFFFF);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            text.setTypeface(UiFont.get(context, R.string.font_regular));
            text.setMaxWidth(dp(520));
            row.addView(text);
            return;
        }
        for (int i = 0; i < result.items.size(); i++) {
            row.addView(card(result.items.get(i), i == 0, i == result.items.size() - 1));
        }
    }

    private View card(final Feed.Item item, final boolean first, final boolean last) {
        LinearLayout card = new LinearLayout(context);
        card.setFocusable(true);
        card.setClickable(true);
        card.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.animate().scaleX(hasFocus ? 1.05f : 1f).scaleY(hasFocus ? 1.05f : 1f).setDuration(120).start();
                v.setBackground(hasFocus ? focusRing() : null);
            }
        });
        card.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        navigator.down();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        navigator.up();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return first;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return last;
                    default:
                        return false;
                }
            }
        });
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open(item);
            }
        });
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(6));   // room for the focus ring around the picture and the text
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.setMarginEnd(dp(4));
        card.setLayoutParams(cp);

        final ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x33FFFFFF);
        bg.setCornerRadius(dp(8));
        image.setBackground(bg);
        image.setClipToOutline(true);
        image.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.addView(image, new LinearLayout.LayoutParams(dp(100), dp(56)));
        load(item.imageUrl, image);

        card.addView(label(item.title, 12, R.string.font_medium, 0xFFFFFFFF, 6));
        if (item.subtitle != null && !item.subtitle.isEmpty()) card.addView(label(item.subtitle, 10, R.string.font_regular, 0xA6FFFFFF, 0));
        return card;
    }

    private GradientDrawable focusRing() {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setColor(0x22FFFFFF);
        ring.setStroke(dp(2), 0xFFFFFFFF);
        return ring;
    }

    private void open(Feed.Item item) {
        Intent intent = null;
        if (item.uri != null) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.uri)).setPackage(current);
        }
        try {
            if (intent == null) throw new ActivityNotFoundException();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(current);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        }
    }

    private TextView label(String text, int sp, int font, int color, int topDp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTypeface(UiFont.get(context, font));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        view.setLayoutParams(lp);
        return view;
    }

    private void load(final String url, final ImageView into) {
        if (url == null) return;
        Bitmap hit = images.get(url);
        if (hit != null) {
            into.setImageBitmap(hit);
            return;
        }
        io.execute(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(6000);
                    c.setReadTimeout(8000);
                    try (InputStream in = c.getInputStream()) {
                        final Bitmap bmp = BitmapFactory.decodeStream(in);
                        if (bmp == null) return;
                        images.put(url, bmp);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                into.setImageBitmap(bmp);
                            }
                        });
                    }
                } catch (Exception ignored) {
                    // no picture: the card keeps its plain background
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * density);
    }
}
