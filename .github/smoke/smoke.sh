#!/usr/bin/env bash
# Smoke test on an Android emulator against the live https://eblo.id (as a guest).
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
  # WebView exposes its accessibility tree once queried; the second dump is the reliable one.
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1 || : > "$OUT/$1.xml"
}
has() { python3 "$HERE/ui.py" has "$OUT/$1.xml" "$2"; }
lacks() { ! has "$@"; }
tap() { # tap a node from dump $1 whose text equals $2
  local xy; xy="$(python3 "$HERE/ui.py" find "$OUT/$1.xml" "$2")" || return 1
  adb shell input tap $xy
}
focus() { adb shell dumpsys window | grep -m1 'mCurrentFocus' | tee -a "$OUT/log.txt"; }
focused_on() { focus | grep -q "$1"; }
open_url() { adb shell am start -W -a android.intent.action.VIEW -d "$2" -n "$1/$PKG.MainActivity" >/dev/null; }
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
wait_page() { # wait until location contains $1 and the document finished loading
  for _ in $(seq 1 40); do
    if [ "$(js "location.href.indexOf('$1') >= 0 && document.readyState === 'complete'" 2>/dev/null)" = "true" ]; then return 0; fi
    sleep 1
  done
  return 1
}

# ---- test data from the site's documented public API ----
log "Picking a post from the feed"
for code in $(curl -fsS https://eblo.id/ | grep -oE 'href="/[A-Za-z0-9]{7}"' | sed -E 's/href="\/(.*)"/\1/' | grep -vE '^(consent|privacy|uploads|sitemap)$' | awk '!seen[$0]++'); do
  post_json="$(curl -fsS "https://eblo.id/api/public/post/$code" || true)"
  TITLE="$(printf '%s' "$post_json" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("title") or "")' 2>/dev/null || true)"
  AUTHOR="$(printf '%s' "$post_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["author"]["login"])' 2>/dev/null || true)"
  if [ "${#TITLE}" -ge 4 ] && [ -n "$AUTHOR" ]; then CODE="$code"; break; fi
done
log "post=$CODE author=$AUTHOR title=$TITLE"
TITLE_PART="${TITLE:0:12}"

# ============ Part A: the signed release APK, as the user gets it ============
log "Installing release APK"
adb install -r "$RELEASE_APK" | tee -a "$OUT/log.txt"
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
sleep 20
dump a1-notice; shot a1-notice
check "release: first-run 'unofficial client' notice is shown" has a1-notice "Неофициальный клиент"
tap a1-notice "Понятно"
sleep 8
dump a2-feed; shot a2-feed
check "release: feed is rendered (tabs Лента/Подписки)" bash -c "python3 '$HERE/ui.py' has '$OUT/a2-feed.xml' 'Подписки' && python3 '$HERE/ui.py' has '$OUT/a2-feed.xml' 'Лента'"
check "release: app stays in foreground" focused_on "$PKG/"

open_url "$PKG" "https://eblo.id/$CODE"
sleep 10
dump a3-post; shot a3-post
check "release: post page opens from an eblo.id link (/$CODE)" has a3-post "$TITLE_PART"

open_url "$PKG" "https://eblo.id/@$AUTHOR"
sleep 10
dump a4-profile; shot a4-profile
check "release: profile page opens (/@$AUTHOR)" has a4-profile "$AUTHOR"

adb shell input keyevent KEYCODE_BACK
sleep 6
dump a5-back; shot a5-back
check "release: Back returns to the previous page (the post)" has a5-back "$TITLE_PART"
check "release: Back did not close the app" focused_on "$PKG/"

log "Going offline"
adb shell cmd connectivity airplane-mode enable
sleep 8
open_url "$PKG" "https://eblo.id/videos"
sleep 8
dump a6-offline; shot a6-offline
check "release: offline screen is shown" has a6-offline "Нет подключения к интернету"
check "release: offline screen has a retry button" has a6-offline "Повторить"
log "Back online"
adb shell cmd connectivity airplane-mode disable
sleep 35
dump a7-online; shot a7-online
check "release: page reloads by itself when the network returns" lacks a7-online "Нет подключения к интернету"
check "release: /videos is shown after reconnect" has a7-online "Видосы"

# ============ Part B: debug APK (same code, WebView debugging on) ============
log "Installing debug APK"
adb install -r "$DEBUG_APK" | tee -a "$OUT/log.txt"
# The first-run notice was already checked above; skip it in the debug build.
adb shell "run-as $DBG mkdir -p shared_prefs"
printf '%s' '<map><boolean name="notice_shown_v1" value="true" /></map>' \
  | adb shell "run-as $DBG sh -c 'cat > shared_prefs/app.xml'"
adb shell am start -W -n "$DBG/$PKG.MainActivity" >/dev/null
sleep 5
dump b0-start
tap b0-start "Понятно" && sleep 2
cdp_forward
check "debug: feed loads" wait_page "eblo.id/"
log "posts on feed: $(js "document.querySelectorAll('a[href^=\"/@\"]').length")"
check "debug: feed contains posts" test "$(js "document.querySelectorAll('a[href^=\"/@\"]').length > 3")" = "true"

js --gesture "(function(){var a=document.querySelector('a[href=\"/$CODE\"]'); if(!a){location.href='/$CODE';return 'nav'} a.click(); return 'click'})()" | tee -a "$OUT/log.txt"
check "debug: tapping a post opens it" wait_page "/$CODE"
check "debug: post title matches the public API" test "$(js "document.body.textContent.indexOf($(printf '%s' "$TITLE" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')) >= 0")" = "true"
shot b1-post

js --gesture "(function(){var a=document.querySelector('a[href=\"/@$AUTHOR\"]'); if(a){a.click(); return 'click'} location.href='/@$AUTHOR'; return 'nav'})()" | tee -a "$OUT/log.txt"
check "debug: tapping the author opens the profile" wait_page "/@$AUTHOR"
adb shell input keyevent KEYCODE_BACK
check "debug: Back goes from the profile to the post" wait_page "/$CODE"

log "External link (t.me) must leave the WebView"
js --gesture "(function(){var a=document.createElement('a'); a.href='https://t.me/ebloid_upd'; document.body.appendChild(a); a.click(); return true})()" >/dev/null
sleep 5
focus
check "debug: external link did not navigate the WebView" test "$(js "location.host")" = '"eblo.id"'
adb shell am start -W -n "$DBG/$PKG.MainActivity" >/dev/null
sleep 2

log "Login link must open Twitch inside the app"
js --gesture "(function(){var a=document.querySelector('a[href^=\"/login/twitch\"]'); if(!a) return 'none'; a.click(); return a.getAttribute('href')})()" | tee -a "$OUT/log.txt"
sleep 15
cdp_forward
log "location during login: $(js 'location.href' | sed -E 's/(state|client_id)=[^&\"]*/\1=…/g')"
check "debug: Twitch authorization page is loaded inside the app" test "$(js "/(^|\\.)twitch\\.tv$/.test(location.host)")" = "true"
check "debug: app stays in foreground during login" focused_on "$DBG/"
shot b2-twitch-login
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 5
cdp_forward
log "after Back from Twitch: $(js 'location.href')"
check "debug: Back from the Twitch page returns to eblo.id" wait_page "eblo.id"

log "File input must open the Android system file picker"
adb shell 'echo "smoke test" > /sdcard/Download/smoke-test.txt'
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/smoke-test.txt >/dev/null
js --gesture "(function(){var i=document.createElement('input'); i.type='file'; i.accept='.txt,.zip,image/*'; i.id='smoke-file'; i.style.display='none'; window.__picked=null; i.addEventListener('change',function(){window.__picked=Array.from(i.files).map(function(f){return f.name})}); i.addEventListener('cancel',function(){window.__picked='cancel'}); document.body.appendChild(i); i.click(); return true})()" >/dev/null
sleep 6
focus
check "debug: system file picker is shown" focused_on "documentsui"
dump b3-picker; shot b3-picker
if tap b3-picker "smoke-test.txt"; then
  sleep 5
  log "picked: $(js 'window.__picked')"
  check "debug: chosen file is handed to the page" test "$(js 'JSON.stringify(window.__picked)')" = '"[\"smoke-test.txt\"]"'
else
  log "smoke-test.txt not visible in the picker's first screen; cancelling instead"
  adb shell input keyevent KEYCODE_BACK
  sleep 4
  check "debug: cancelling the picker returns to the app" focused_on "$DBG/"
fi
shot b4-after-picker
check "debug: a second file input click opens the picker again (callback completed)" bash -c "
  python3 '$HERE/cdp.py' --gesture \"document.getElementById('smoke-file').click(), true\" >/dev/null; sleep 5;
  adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q documentsui"
adb shell input keyevent KEYCODE_BACK
sleep 2

# ---- crashes ----
adb logcat -d > "$OUT/logcat.txt"
check "no app crash in logcat" bash -c "! grep -E 'FATAL EXCEPTION|Process: $PKG' '$OUT/logcat.txt' | grep -q ."

rm -f "$OUT"/tmp*.xml
echo
cat "$OUT/summary.txt"
echo "failures: $FAILS"
exit "$FAILS"
