package io.github.koffelov.ebloclient;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.PopupMenu;

public class ProfileActivity extends BaseActivity {
    private static final String EXTRA_LOGIN = "login";
    private ProfileController controller;
    private boolean resumedOnce;

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
        addAction(R.drawable.ic_more, "Ещё", v -> {
            PopupMenu menu = new PopupMenu(this, v);
            menu.getMenu().add("Открыть на сайте");
            menu.setOnMenuItemClickListener(item -> {
                WebActivity.open(this, Http.BASE + "/@" + login, "@" + login);
                return true;
            });
            menu.show();
        });
        controller = new ProfileController(this, login);
        page.addView(controller.view(), new LinearLayout.LayoutParams(-1, 0, 1f));
        controller.load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back from "Профиль и настройки" shows the new avatar and badge.
        if (resumedOnce && isOwn(getIntent().getStringExtra(EXTRA_LOGIN))) controller.load(false);
        resumedOnce = true;
    }

    private static boolean isOwn(String login) {
        return Session.loggedIn() && login != null && login.equalsIgnoreCase(Session.login());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebActivity.REQUEST_LOGIN && resultCode == RESULT_OK) controller.load(false);
    }
}
