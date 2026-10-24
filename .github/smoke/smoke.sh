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
APP_ANR=0
SYSTEM_ANR=0
# Emulators on CI decode video in software and the Android "system" process sometimes stops
# responding. Dismiss that dialog ("Wait") so it doesn't hide the app; an ANR of the app itself
# is recorded and fails the run.
anr_guard() {
  local f
  f="$(adb shell dumpsys window | grep -m1 'mCurrentFocus')"
  case "$f" in
    *"Application Not Responding: $PKG"*|*"Application Not Responding: $DBG"*)
      APP_ANR=$((APP_ANR + 1)); log "APP ANR: $f" ;;
    *"Application Not Responding"*)
      SYSTEM_ANR=$((SYSTEM_ANR + 1)); log "emulator system ANR dialog dismissed: $f" ;;
    *) return 0 ;;
  esac
  dump anr
  shot "anr-$APP_ANR-$SYSTEM_ANR"
  tap anr "Wait" || adb shell input keyevent KEYCODE_BACK
  sleep 2
}
snap() { anr_guard; dump "$1"; shot "$1"; }
has() { python3 "$HERE/ui.py" has "$OUT/$1.xml" "$2"; }
lacks() { ! has "$@"; }
tap() { local xy; xy="$(python3 "$HERE/ui.py" find "$OUT/$1.xml" "$2")" || return 1; adb shell input tap $xy; }
tapsub() { local xy; xy="$(python3 "$HERE/ui.py" findsub "$OUT/$1.xml" "$2")" || return 1; adb shell input tap $xy; }
focus() { adb shell dumpsys window | grep -m1 'mCurrentFocus' | tee -a "$OUT/log.txt"; }
focused_on() { focus | grep -q "$1"; }
no_webview() { ! grep -q 'android.webkit.WebView' "$OUT/$1.xml"; }
open_url() { adb shell am start -W -a android.intent.action.VIEW -d "$2" -n "$1/$PKG.MainActivity" >/dev/null; }
back() { adb shell input keyevent KEYCODE_BACK; sleep "${1:-3}"; }
main() { adb shell am start -W -n "$1/$PKG.MainActivity" >/dev/null; sleep 2; }
js() { python3 "$HERE/cdp.py" "$@"; }
cdp_forward() {
  # The debug app's own DevTools socket (other apps on the image can have WebViews too).
  local sock pid
  for _ in $(seq 1 30); do
    pid="$(adb shell pidof "$DBG" | tr -d '\r' | awk '{print $1}')"
    sock="$(adb shell grep -a -o "webview_devtools_remote_$pid\$" /proc/net/unix | tr -d '\r' | head -1)"
    [ -n "$pid" ] && [ -n "$sock" ] && break
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
VIDEO_CODE="$(curl -sS 'https://eblo.id/api/feed?scope=public&sort=new&type=video&offset=0&limit=5' \
  | jsonget "next((f['short_code'] for f in d['files'] if not f.get('is_album') and f.get('blur') in ('0', 0, False, None)), '')" 2>/dev/null || true)"
log "feed title=$FEED_TITLE | post=$CODE author=$AUTHOR title=$TITLE | video=$VIDEO_CODE"
if [ -z "$CODE" ] || [ -z "$FEED_TITLE" ]; then
  log "Could not get test data from eblo.id: $(printf '%s' "$FEED_JSON" | head -c 300)"
  exit 1
fi
# Big log buffers, so an ANR early in the run is still in logcat at the end.
adb logcat -G 16M >/dev/null 2>&1 || true
adb logcat -c || true
adb shell 'echo "smoke test" > /sdcard/Download/smoke-test.txt'
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/smoke-test.txt >/dev/null

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
check "native feed: tabs and bottom bar" bash -c "python3 '$HERE/ui.py' has '$OUT/a02-feed.xml' 'Подписки' && python3 '$HERE/ui.py' has '$OUT/a02-feed.xml' 'Видосы'"
check "native feed shows the first post from /api/feed" has a02-feed "${FEED_TITLE:0:10}"
check "feed screen is native (no WebView)" no_webview a02-feed

tapsub a02-feed "${FEED_TITLE:0:10}"
sleep 8
snap a03-post-from-feed
check "tapping a card opens the native post" bash -c "python3 '$HERE/ui.py' has '$OUT/a03-post-from-feed.xml' 'Комментарии'"
back 4
snap a04-back-to-feed
check "Back returns to the feed" has a04-back-to-feed "Подписки"

open_url "$PKG" "https://eblo.id/$CODE"
sleep 8
snap a05-post-link
check "eblo.id post link opens the native post (/$CODE)" has a05-post-link "${TITLE:0:10}"
check "guest sees 'log in to comment'" has a05-post-link "Войдите, чтобы комментировать"

open_url "$PKG" "https://eblo.id/@bratishkinoff"
sleep 8
snap a06-profile-telegram
check "profile with a linked Telegram shows it (@bratishkinoff)" has a06-profile-telegram "Telegram"
check "profile is native with counters" bash -c "python3 '$HERE/ui.py' has '$OUT/a06-profile-telegram.xml' 'публикаций' && ! grep -q 'android.webkit.WebView' '$OUT/a06-profile-telegram.xml'"
open_url "$PKG" "https://eblo.id/@ishak_kodolov"
sleep 8
snap a07-profile-no-telegram
check "profile without Telegram has no Telegram icon (@ishak_kodolov)" bash -c "python3 '$HERE/ui.py' has '$OUT/a07-profile-no-telegram.xml' '@ishak_kodolov' && ! python3 '$HERE/ui.py' has '$OUT/a07-profile-no-telegram.xml' 'Telegram'"

if [ -n "$VIDEO_CODE" ]; then
  log "Video player"
  open_url "$PKG" "https://eblo.id/$VIDEO_CODE"
  sleep 8
  snap a08-video-post
  check "video post shows the app's player" has a08-video-post "Воспроизвести"
  tap a08-video-post "Воспроизвести"
  # The CI emulator has no audio device, so video runs faster than real time and a short clip can
  # end within seconds. Pass when the player shows a position past 0:00 (playing or finished).
  played=""
  for i in 1 2 3 4 5 6 7 8; do
    sleep 1
    anr_guard
    dump "a09-poll-$i"
    if ! has "a09-poll-$i" "На весь экран"; then
      xy="$(python3 "$HERE/ui.py" findstart "$OUT/a09-poll-$i.xml" "Видео ")" && adb shell input tap $xy
      sleep 1
      dump "a09-poll-$i"
    fi
    t="$(python3 "$HERE/ui.py" match "$OUT/a09-poll-$i.xml" '[0-9]+:[0-9]{2} / [0-9]+:[0-9]{2}' || true)"
    log "player poll $i: '$t' pause=$(has "a09-poll-$i" 'Пауза' && echo yes || echo no)"
    case "$t" in ""|"0:00 /"*) ;; *) played="$t"; break ;; esac
  done
  shot a09-video-playing
  cp "$OUT/a09-poll-$i.xml" "$OUT/a09-video-playing.xml"
  check "inline video loads and plays (position: ${played:-none})" test -n "$played"
  if ! has a09-video-playing "На весь экран"; then
    xy="$(python3 "$HERE/ui.py" findstart "$OUT/a09-video-playing.xml" "Видео ")" && adb shell input tap $xy
    sleep 1
    dump a09-video-playing
  fi
  tap a09-video-playing "На весь экран"
  sleep 6
  anr_guard
  dump a10-video-fullscreen
  if ! has a10-video-fullscreen "Свернуть"; then
    # Controls auto-hide while playing; a tap in the middle brings them back.
    adb shell input tap $(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | awk -Fx '{print int($1/2), int($2/2)}')
    sleep 1
  fi
  snap a10-video-fullscreen
  check "fullscreen player opens" focused_on "MediaActivity"
  check "fullscreen player shows its controls" has a10-video-fullscreen "Свернуть"
  back 3
  anr_guard
  snap a11-after-fullscreen
  check "Back from fullscreen returns to the post" focused_on "PostActivity"
  check "inline player released its decoder while fullscreen (play button back)" has a11-after-fullscreen "Воспроизвести"
fi

log "Search"
main "$PKG"
dump a11-main
tap a11-main "Поиск"
sleep 3
adb shell input text "brat"
adb shell input keyevent KEYCODE_ENTER
sleep 8
snap a12-search
check "search returns native results" bash -c "python3 '$HERE/ui.py' has '$OUT/a12-search.xml' 'Пользователи' || python3 '$HERE/ui.py' has '$OUT/a12-search.xml' 'Публикации'"
back 2
back 3

log "Videos tab and native 'suggest a video'"
main "$PKG"
dump a13-main
tap a13-main "Видосы"
sleep 8
snap a14-videos
check "Видосы: native list with categories and likes" bash -c "python3 '$HERE/ui.py' has '$OUT/a14-videos.xml' 'Рекомендуем' && python3 '$HERE/ui.py' has '$OUT/a14-videos.xml' 'Голосовать за видео'"
tap a14-videos "Предложить видео"
sleep 5
snap a15-suggest
check "'Предложить' opens the native suggest screen" bash -c "adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q SuggestVideoActivity && ! grep -q 'android.webkit.WebView' '$OUT/a15-suggest.xml'"
check "suggest screen asks a guest to log in" has a15-suggest "предлагать видео"
back 3

log "Native new post"
main "$PKG"
dump a16-main
tap a16-main "Новый пост"
sleep 4
snap a17-new-post
check "+ opens the native new-post screen" bash -c "adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q NewPostActivity && ! grep -q 'android.webkit.WebView' '$OUT/a17-new-post.xml'"
check "new post: gallery, files and publish controls" bash -c "python3 '$HERE/ui.py' has '$OUT/a17-new-post.xml' 'Галерея' && python3 '$HERE/ui.py' has '$OUT/a17-new-post.xml' 'Опубликовать'"
check "new post: guest is told to log in" has a17-new-post "чтобы опубликовать пост"
tap a17-new-post "Файлы"
sleep 6
check "Files opens the Android system picker" focused_on "documentsui"
snap a18-picker
if tap a18-picker "smoke-test.txt"; then
  sleep 5
  snap a19-attached
  check "picked file appears in the new post" bash -c "python3 '$HERE/ui.py' has '$OUT/a19-attached.xml' 'smoke-test.txt' && python3 '$HERE/ui.py' has '$OUT/a19-attached.xml' '1 из 10'"
  tap a19-attached "Опубликовать"
  sleep 3
  snap a20-publish-guest
  check "publishing as a guest asks to log in (nothing is posted)" has a20-publish-guest "Нужен вход"
  tap a20-publish-guest "Отмена" || back 2
else
  log "smoke-test.txt not on the picker's first screen"
  back 3
fi
back 2
dump a21-leave
tap a21-leave "Выйти" || true
sleep 2

log "Offline"
adb shell cmd connectivity airplane-mode enable
for _ in $(seq 1 20); do
  adb shell ping -c 1 -W 1 8.8.8.8 >/dev/null 2>&1 || break
  sleep 1
done
sleep 2
open_url "$PKG" "https://eblo.id/$CODE"
sleep 8
snap a22-offline
check "offline: clear message and retry" bash -c "python3 '$HERE/ui.py' has '$OUT/a22-offline.xml' 'Нет подключения к интернету' && python3 '$HERE/ui.py' has '$OUT/a22-offline.xml' 'Повторить'"
adb shell cmd connectivity airplane-mode disable
sleep 30
dump a23-retry
tap a23-retry "Повторить"
sleep 8
snap a24-online
check "retry loads the post after reconnect" has a24-online "${TITLE:0:10}"
back 3

# ============ Part B: debug APK — Twitch login page and cookies via DevTools ============
log "Installing debug APK"
adb install -r "$DEBUG_APK" | tee -a "$OUT/log.txt"
adb shell "run-as $DBG mkdir -p shared_prefs"
printf '%s' '<map><boolean name="notice_shown_v1" value="true" /></map>' \
  | adb shell "run-as $DBG sh -c 'cat > shared_prefs/app.xml'"
main "$DBG"
sleep 4
dump b01-main
tap b01-main "Войти"
sleep 3
dump b02-me
tap b02-me "Войти через Twitch"
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
open_url "$DBG" "https://eblo.id/$CODE"
sleep 8
dump b04-post
tap b04-post "Ещё"
sleep 2
dump b05-menu
tap b05-menu "Открыть на сайте"
sleep 10
cdp_forward
check "debug: session cookie survives force-stop and relaunch" has_session_cookie

# ---- crashes ----
adb logcat -d > "$OUT/logcat.txt"
check "no app crash in logcat" bash -c "! grep -E 'FATAL EXCEPTION' -A3 '$OUT/logcat.txt' | grep -q 'koffelov'"
grep -E 'ANR in ' "$OUT/logcat.txt" | tee -a "$OUT/log.txt" || true
check "the app itself never stopped responding (ANR)" bash -c "[ $APP_ANR -eq 0 ] && ! grep -qE 'ANR in io\.github\.koffelov' '$OUT/logcat.txt'"
log "emulator system ANR dialogs dismissed: $SYSTEM_ANR"

echo
cat "$OUT/summary.txt"
echo "failures: $FAILS"
exit "$FAILS"
