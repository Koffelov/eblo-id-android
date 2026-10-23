#!/usr/bin/env bash
# Smoke test on an Android emulator against the live https://eblo.id, as a guest.
# Never logs in and never posts anything.
#   smoke.sh RELEASE_APK DEBUG_APK OUT_DIR
set -uo pipefail

RELEASE_APK="$1"
DEBUG_APK="$2"
OUT="$3"
HERE="$(cd "$(dirname "$0")" && pwd)"
PKG=io.github.koffelov.ebloclient
DBG=$PKG.debug
mkdir -p "$OUT"
: > "$OUT/summary.txt"
FAILS=0

log() { echo "== $*" | tee -a "$OUT/log.txt"; }
check() {
  local name="$1"; shift
  if "$@"; then echo "PASS  $name" | tee -a "$OUT/summary.txt"
  else echo "FAIL  $name" | tee -a "$OUT/summary.txt"; FAILS=$((FAILS + 1)); fi
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
dump() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 || : > "$OUT/$1.xml"
}
snap() { dump "$1"; shot "$1"; }
has() { python3 "$HERE/ui.py" has "$OUT/$1.xml" "$2"; }
lacks() { ! has "$@"; }
tap() { local xy; xy="$(python3 "$HERE/ui.py" find "$OUT/$1.xml" "$2")" || return 1; adb shell input tap $xy; }
tapsub() { local xy; xy="$(python3 "$HERE/ui.py" findsub "$OUT/$1.xml" "$2")" || return 1; adb shell input tap $xy; }
focus() { adb shell dumpsys window | grep -m1 'mCurrentFocus' | tee -a "$OUT/log.txt"; }
focused_on() { focus | grep -q "$1"; }
open_url() { adb shell am start -W -a android.intent.action.VIEW -d "$2" -n "$1/$PKG.MainActivity" >/dev/null; }
back() { adb shell input keyevent KEYCODE_BACK; sleep "${1:-3}"; }
js() { python3 "$HERE/cdp.py" "$@"; }
cdp_forward() {
  local sock
  for _ in $(seq 1 30); do
    sock="$(adb shell grep -a -o 'webview_devtools_remote_[0-9]*' /proc/net/unix | tr -d '\r' | head -1)"
    [ -n "$sock" ] && break
    sleep 1
  done
  adb forward --remove-all
  adb forward tcp:9222 "localabstract:$sock" >/dev/null
}
wait_page() {
  for _ in $(seq 1 40); do
    if [ "$(js "location.href.indexOf('$1') >= 0 && document.readyState === 'complete'" 2>/dev/null)" = "true" ]; then return 0; fi
    sleep 1
  done
  return 1
}
jsonget() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# ---- expected data, straight from the site ----
log "site from this runner: home $(curl -s -o /dev/null -w '%{http_code}' https://eblo.id/), feed api $(curl -s -o /dev/null -w '%{http_code}' 'https://eblo.id/api/feed?limit=1')"
FEED_JSON="$(curl -sS 'https://eblo.id/api/feed?scope=public&sort=best&type=all&time=today&offset=0&limit=15')"
FEED_TITLE="$(printf '%s' "$FEED_JSON" | jsonget "d['files'][0]['title']" 2>/dev/null || true)"
CODE="" AUTHOR="" TITLE=""
for code in $(printf '%s' "$FEED_JSON" | jsonget "' '.join(f['short_code'] for f in d['files'] if len(f.get('title') or '') >= 4)" 2>/dev/null); do
  post_json="$(curl -sS "https://eblo.id/api/public/post/$code" || true)"
  TITLE="$(printf '%s' "$post_json" | jsonget "d.get('title') or ''" 2>/dev/null || true)"
  AUTHOR="$(printf '%s' "$post_json" | jsonget "d['author']['login']" 2>/dev/null || true)"
  if [ "${#TITLE}" -ge 4 ] && [ -n "$AUTHOR" ]; then CODE="$code"; break; fi
done
log "feed title=$FEED_TITLE | post=$CODE author=$AUTHOR title=$TITLE"
if [ -z "$CODE" ] || [ -z "$FEED_TITLE" ]; then
  log "Could not get test data from eblo.id: $(printf '%s' "$FEED_JSON" | head -c 300)"
  exit 1
fi

# ============ Part A: the signed release APK, as the user installs it ============
log "Installing release APK"
adb install -r "$RELEASE_APK" | tee -a "$OUT/log.txt"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 15
snap a01-notice
check "first-run 'unofficial client' notice" has a01-notice "Неофициальный клиент"
tap a01-notice "Понятно"
sleep 6
snap a02-feed
check "native feed: Лента/Подписки tabs" bash -c "python3 '$HERE/ui.py' has '$OUT/a02-feed.xml' 'Подписки' && python3 '$HERE/ui.py' has '$OUT/a02-feed.xml' 'Видосы'"
check "native feed shows the first post from /api/feed" has a02-feed "${FEED_TITLE:0:10}"
check "feed screen is native (no WebView)" bash -c "! grep -q 'android.webkit.WebView' '$OUT/a02-feed.xml'"

log "Open the first post by tapping it"
tapsub a02-feed "${FEED_TITLE:0:10}"
sleep 8
snap a03-post-from-feed
check "tapping a card opens the native post screen" bash -c "python3 '$HERE/ui.py' has '$OUT/a03-post-from-feed.xml' 'Комментарии' && python3 '$HERE/ui.py' has '$OUT/a03-post-from-feed.xml' '${FEED_TITLE:0:10}'"
back 4
snap a04-back-to-feed
check "Back returns to the feed" has a04-back-to-feed "Подписки"

open_url "$PKG" "https://eblo.id/$CODE"
sleep 8
snap a05-post-link
check "eblo.id post link opens the native post (/$CODE)" has a05-post-link "${TITLE:0:10}"
check "post shows comments section" has a05-post-link "Комментарии"
check "guest sees 'log in to comment'" has a05-post-link "Войдите, чтобы комментировать"

open_url "$PKG" "https://eblo.id/@$AUTHOR"
sleep 8
snap a06-profile
check "profile link opens the native profile (@$AUTHOR)" has a06-profile "@$AUTHOR"
check "profile shows counters" has a06-profile "публикаций"
back 3
back 3
back 3

log "Search"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2
dump a07-main
tap a07-main "Поиск"
sleep 3
adb shell input text "brat"
adb shell input keyevent KEYCODE_ENTER
sleep 8
snap a08-search
check "search returns native results" bash -c "python3 '$HERE/ui.py' has '$OUT/a08-search.xml' 'Пользователи' || python3 '$HERE/ui.py' has '$OUT/a08-search.xml' 'Публикации'"
back 2
back 3

log "Tabs"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2
dump a09-main
tap a09-main "Видосы"
sleep 8
snap a10-videos
check "Видосы tab: native list with categories" bash -c "python3 '$HERE/ui.py' has '$OUT/a10-videos.xml' 'Рекомендуем' && python3 '$HERE/ui.py' has '$OUT/a10-videos.xml' 'Предложить'"
tap a10-videos "Уведомления"
sleep 4
snap a11-notifications
check "Уведомления tab asks a guest to log in" has a11-notifications "Войти через Twitch"
tap a11-notifications "Войти"
sleep 3
snap a12-me
check "profile tab asks a guest to log in" has a12-me "Войти через Twitch"

log "Login opens Twitch inside the app"
tap a12-me "Войти через Twitch"
sleep 15
snap a13-login
check "login opens the in-app web screen" focused_on "$PKG/$PKG.WebActivity"
check "login screen has the native top bar" has a13-login "Вход через Twitch"
back 3
back 3
back 3
focus

log "New post opens the site's uploader in the app"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 2
dump a14-main
tap a14-main "Новый пост"
sleep 12
snap a15-new-post
check "+ opens the 'Новый пост' web screen" bash -c "adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q WebActivity && python3 '$HERE/ui.py' has '$OUT/a15-new-post.xml' 'Новый пост'"
back 3
back 3

log "Offline"
adb shell cmd connectivity airplane-mode enable
sleep 8
open_url "$PKG" "https://eblo.id/$CODE"
sleep 6
snap a16-offline
check "offline: clear message" has a16-offline "Нет подключения к интернету"
check "offline: retry button" has a16-offline "Повторить"
adb shell cmd connectivity airplane-mode disable
sleep 30
dump a17-retry
tap a17-retry "Повторить"
sleep 8
snap a18-online
check "retry loads the post after reconnect" has a18-online "${TITLE:0:10}"
back 3

# ============ Part B: debug APK — web screens via DevTools ============
log "Installing debug APK"
adb install -r "$DEBUG_APK" | tee -a "$OUT/log.txt"
adb shell "run-as $DBG mkdir -p shared_prefs"
printf '%s' '<map><boolean name="notice_shown_v1" value="true" /></map>' \
  | adb shell "run-as $DBG sh -c 'cat > shared_prefs/app.xml'"
adb shell am start -W -n "$DBG/$PKG.MainActivity" >/dev/null
sleep 6
dump b01-main
tap b01-main "Новый пост"
sleep 10
cdp_forward
check "debug: new-post page is eblo.id/new" wait_page "eblo.id/new"
check "debug: site header and tab bar are hidden in web screens" test \
  "$(js "(function(){var t=document.getElementById('mobile-tabbar'); return !t || getComputedStyle(t).display==='none'})()")" = "true"

log "File input must open the Android system file picker"
adb shell 'echo "smoke test" > /sdcard/Download/smoke-test.txt'
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/smoke-test.txt >/dev/null
js --gesture "(function(){var i=document.createElement('input'); i.type='file'; i.accept='.txt,.zip,image/*'; i.id='smoke-file'; i.style.display='none'; window.__picked=null; i.addEventListener('change',function(){window.__picked=Array.from(i.files).map(function(f){return f.name})}); document.body.appendChild(i); i.click(); return true})()" >/dev/null
sleep 6
check "debug: system file picker is shown" focused_on "documentsui"
snap b02-picker
if tap b02-picker "smoke-test.txt"; then
  sleep 5
  log "picked: $(js 'window.__picked')"
  check "debug: chosen file is handed to the page" test "$(js 'JSON.stringify(window.__picked)')" = '"[\"smoke-test.txt\"]"'
else
  log "smoke-test.txt not on the picker's first screen; cancelling instead"
  back 4
  check "debug: cancelling the picker returns to the app" focused_on "$DBG/"
fi
check "debug: picker opens again (callback completed)" bash -c "
  python3 '$HERE/cdp.py' --gesture \"document.getElementById('smoke-file').click(), true\" >/dev/null; sleep 5;
  adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q documentsui"
back 3

log "Login flow stays in the app and sets the eblo.id session cookie"
js --gesture "(function(){var a=document.querySelector('a[href^=\"/login/twitch\"]'); if(a){a.click(); return 'click'} location.href='/login/twitch?next=/new'; return 'nav'})()" | tee -a "$OUT/log.txt"
sleep 15
cdp_forward
log "location during login: $(js 'location.host')"
check "debug: Twitch authorization page loads inside the app" test "$(js "/(^|\\.)twitch\\.tv$/.test(location.host)")" = "true"
check "debug: app stays in foreground during login" focused_on "$DBG/"
shot b03-twitch
has_session_cookie() {
  js --call Network.getCookies '{"urls":["https://eblo.id/"]}' \
    | python3 -c 'import json,sys; c=json.load(sys.stdin).get("cookies",[]); sys.exit(0 if any(x["name"]=="session" for x in c) else 1)'
}
check "debug: eblo.id session cookie is stored" has_session_cookie
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell am force-stop "$DBG"
adb shell am start -W -n "$DBG/$PKG.MainActivity" >/dev/null
sleep 5
dump b04-main
tap b04-main "Новый пост"
sleep 8
cdp_forward
check "debug: session cookie survives force-stop and relaunch" has_session_cookie

# ---- crashes ----
adb logcat -d > "$OUT/logcat.txt"
check "no app crash in logcat" bash -c "! grep -E 'FATAL EXCEPTION' -A3 '$OUT/logcat.txt' | grep -q 'koffelov'"

echo
cat "$OUT/summary.txt"
echo "failures: $FAILS"
exit "$FAILS"
