package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/** A source of recent titles for one app, shown beside the app's name on the top shelf (FeedRow). */
interface Feed {
    String packageName();

    /** Blocking: runs on FeedRow's worker thread. */
    Result fetch() throws Exception;

    final class Item {
        final String title;
        final String subtitle;
        final String imageUrl;
        /** What OK opens, sent to the feed's app as a VIEW intent; null opens the app itself. */
        final String uri;

        Item(String title, String subtitle, String imageUrl, String uri) {
            this.title = title;
            this.subtitle = subtitle;
            this.imageUrl = imageUrl;
            this.uri = uri;
        }
    }

    /** Either titles to show, or a message to show instead (a sign-in code, an error); retryMs > 0 asks for another fetch. */
    final class Result {
        final List<Item> items;
        final String message;
        final long retryMs;

        Result(List<Item> items, String message, long retryMs) {
            this.items = items == null ? new ArrayList<Item>() : items;
            this.message = message;
            this.retryMs = retryMs;
        }

        static Result items(List<Item> items) {
            return new Result(items, null, 0);
        }

        static Result message(String message, long retryMs) {
            return new Result(null, message, retryMs);
        }
    }
}
