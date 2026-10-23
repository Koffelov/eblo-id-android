package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

public class ProfileActivity extends BaseActivity {
    private static final String EXTRA_LOGIN = "login";
    private ProfileController controller;

    static void start(Context c, String login) {
        if (login == null || login.isEmpty()) return;
        c.startActivity(new Intent(c, ProfileActivity.class).putExtra(EXTRA_LOGIN, login));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String login = getIntent().getStringExtra(EXTRA_LOGIN);
        addTopBar("@" + login, true);
        addAction(R.drawable.ic_share, "Поделиться",
                v -> Router.share(this, "@" + login, Http.BASE + "/@" + login));
        addAction(R.drawable.ic_open, "Открыть на сайте",
                v -> WebActivity.open(this, Http.BASE + "/@" + login, "@" + login));
        controller = new ProfileController(this, login);
        controller.setOnLoggedOut(this::finish);
        page.addView(controller.view(), new LinearLayout.LayoutParams(-1, 0, 1f));
        controller.load(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN && resultCode == RESULT_OK) controller.load(false);
    }
}
