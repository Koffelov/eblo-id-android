package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

/** A feed filtered by #tag or by a search query. */
public class FeedActivity extends BaseActivity {
    private static final String EXTRA_TAG = "tag";
    private static final String EXTRA_QUERY = "query";

    static void startTag(Context c, String tag) {
        c.startActivity(new Intent(c, FeedActivity.class).putExtra(EXTRA_TAG, tag));
    }

    static void startQuery(Context c, String query) {
        c.startActivity(new Intent(c, FeedActivity.class).putExtra(EXTRA_QUERY, query));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Api.FeedQuery q = new Api.FeedQuery();
        q.tag = getIntent().getStringExtra(EXTRA_TAG);
        q.query = getIntent().getStringExtra(EXTRA_QUERY);
        q.sort = "new";
        q.time = "all";
        if (q.query != null) q.sort = "relevance";
        addTopBar(q.tag != null ? "#" + q.tag : "«" + q.query + "»", true);
        FeedController feed = new FeedController(this, q, false);
        page.addView(feed.view(), new LinearLayout.LayoutParams(-1, 0, 1f));
        feed.reload(true);
    }
}
