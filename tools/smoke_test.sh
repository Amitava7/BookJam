#!/usr/bin/env bash
# Installs the release APK on a running emulator and listens to a book the
# way a person would: adds a folder through the system picker, plays, taps
# back and forward, runs off the end of a file, jumps chapters and undoes it,
# changes speed, sets and cancels the sleep timer, leaves for the home screen,
# turns the screen off, kills the app with kill -9 and checks it comes back at
# the same place, and lets the sleep timer close it. Fails on any crash.
#
# Playback is checked through `dumpsys media_session`, which is what the lock
# screen sees. The screen is read with uiautomator only while paused: while
# playing the clock ticks, and uiautomator waits for a still screen.
# Screenshots land in shots/ and the workflow uploads them.
set -euo pipefail

APK=$(ls apk/*.apk | head -1)
PKG=com.bookjam
SHOTS=shots
mkdir -p "$SHOTS"

shot() { adb exec-out screencap -p > "$SHOTS/$1.png" || true; }

# The screen's view hierarchy as XML. uiautomator gives up on a screen that
# will not settle, so try a few times and say why in the log.
ui() {
    local i out err
    for i in 1 2 3 4; do
        adb shell rm -f /sdcard/ui.xml >/dev/null 2>&1 || true
        err=$(adb shell uiautomator dump /sdcard/ui.xml 2>&1 || true)
        out=$(adb shell cat /sdcard/ui.xml 2>/dev/null || true)
        case "$out" in
            *'<hierarchy'*) printf '%s\n' "$out"; return 0 ;;
        esac
        echo "uiautomator dump failed (try $i): $err" >&2
        sleep 1
    done
}

texts() {
    local x
    x=$(ui)
    grep -o 'text="[^"]*"\|content-desc="[^"]*"' <<<"$x" | grep -v '=""' | head -60 || true
}

# Prints what is on screen, for following the run in the log.
show() {
    echo "--- on screen: $1 ($(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' | sed 's/^ *//'))"
    texts | sed 's/^/    /'
}

fail() {
    echo "::error::$*"
    shot "failed"
    show "at the failure"
    exit 1
}

# Centre of the first node whose line contains $1; -i as $2 ignores case.
locate() {
    local x flags=-F
    [ "${2:-}" = "-i" ] && flags=-iF
    x=$(ui)
    tr '<' '\n' <<<"$x" | grep $flags -- "$1" | head -1 \
        | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' | head -1 \
        | tr -c '0-9' ' ' | awk '{print int(($1+$3)/2), int(($2+$4)/2)}' || true
}

tap_xy() { adb shell input tap "$1" "$2"; }

# tap <match> [-i] [seconds to wait after]
tap() {
    local xy
    xy=$(locate "$1" "${2:-}")
    [ -n "$xy" ] || fail "nothing on screen matches $1"
    echo "tap $1 at $xy"
    # shellcheck disable=SC2086
    tap_xy $xy
    sleep "${3:-2}"
}

expect() {
    local x
    x=$(ui)
    grep -qF -- "$1" <<<"$x" || fail "expected to see $1"
}

wait_for() {
    local i x
    for i in $(seq 1 "${2:-15}"); do
        x=$(ui)
        if grep -qiF -- "$1" <<<"$x"; then return 0; fi
        sleep 1
    done
    fail "timed out waiting for $1"
}

crashed() {
    local log
    log=$(adb logcat -b crash -d 2>/dev/null || true)
    if grep -q "Process: $PKG" <<<"$log"; then
        echo "$log"
        fail "BookJam crashed ($1)"
    fi
}

# BookJam's media session, as the lock screen sees it.
session() {
    adb shell dumpsys media_session | tr -d '\r' | awk -v p="$PKG/" 'index($0, p) {f=1} f'
}

# pb state|pos|speed|title
pb() {
    session | python3 -c '
import re, sys
s = sys.stdin.read()
want = sys.argv[1]
if want == "title":
    t = re.search(r"description=([^,\n]*)", s)
    print(t.group(1).strip() if t else "")
    sys.exit()
m = re.search(r"PlaybackState \{state=([A-Z_]+)?\(?(\d+)?\)?, position=(-?\d+).*?speed=([\d.]+)", s)
if not m:
    print("")
    sys.exit()
names = {"0": "NONE", "1": "STOPPED", "2": "PAUSED", "3": "PLAYING", "6": "BUFFERING", "7": "ERROR"}
state = m.group(1) or names.get(m.group(2), m.group(2))
print({"state": state, "pos": m.group(3), "speed": m.group(4)}[want])
' "$1"
}

wait_state() {
    local i
    for i in $(seq 1 "${2:-10}"); do
        [ "$(pb state)" = "$1" ] && return 0
        sleep 1
    done
    session | head -40
    fail "playback is $(pb state), expected $1"
}

# between <what> <value> <low> <high>
between() {
    if [ -z "$2" ] || [ "$2" -lt "$3" ] || [ "$2" -gt "$4" ]; then
        fail "$1 = $2, expected $3 to $4"
    fi
    echo "$1 = $2 (expected $3 to $4)"
}

adb install -r "$APK"
adb root >/dev/null 2>&1 || true
adb wait-for-device
sleep 3
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell settings put system screen_off_timeout 1800000 || true
adb shell wm dismiss-keyguard || true
adb logcat -b crash -c || true

adb shell rm -rf /sdcard/Audiobooks
adb push testbooks/Audiobooks /sdcard/ >/dev/null
adb shell ls -lR /sdcard/Audiobooks

# ---- 1. an empty library -----------------------------------------------------
adb shell am start -W -n "$PKG/.MainActivity"
sleep 4
shot 01-empty-library
crashed launch
expect 'text="No books yet"'

# ---- 2. add the Audiobooks folder through the system folder picker ------------
tap 'text="Add a folder"' "" 4
shot 02-picker
show "folder picker"
if [ -z "$(locate 'text="Audiobooks"' -i)" ]; then
    # Not opened at the top of the phone's storage: get there from the list
    # of roots. The storage root is the one that says how much space is free.
    xy=$(locate 'content-desc="Show roots"' -i)
    # shellcheck disable=SC2086
    if [ -n "$xy" ]; then tap_xy $xy; sleep 2; fi
    shot 02b-roots
    show "picker roots"
    xy=$(locate ' free"' -i)
    # shellcheck disable=SC2086
    if [ -n "$xy" ]; then tap_xy $xy; sleep 3; fi
    show "picker storage root"
fi
wait_for 'text="Audiobooks"' 15
tap 'text="Audiobooks"' -i 3
shot 03-in-audiobooks
show "inside Audiobooks"
tap 'text="Use this folder"' -i 3
shot 04-allow
show "allow access"
tap 'text="Allow"' -i 4

# ---- 3. one book per sub-folder, named after the folder ------------------------
wait_for 'text="Smoke Book"' 30
expect 'text="Short Book"'
expect 'text="2 books"'
shot 05-library
crashed add-folder

# ---- 4. play -------------------------------------------------------------------
tap 'text="Smoke Book"' "" 4
shot 06-player
crashed open-player
expect 'text="Chapter 1"'
[ "$(pb title)" = "Chapter 1" ] || fail "the session says '$(pb title)', not Chapter 1"
# Where the controls are, read once while paused.
PLAY=$(locate 'content-desc="Play"')
BACK=$(locate 'content-desc="Back 10 seconds"')
FWD=$(locate 'content-desc="Forward 10 seconds"')
SPEED=$(locate 'content-desc="Playback speed"')
SLEEP=$(locate 'content-desc="Sleep timer"')
CHAPTERS=$(locate 'content-desc="Chapters"')
for v in "$PLAY" "$BACK" "$FWD" "$SPEED" "$SLEEP" "$CHAPTERS"; do
    [ -n "$v" ] || fail "a player control is missing"
done
echo "play=$PLAY back=$BACK forward=$FWD speed=$SPEED sleep=$SLEEP chapters=$CHAPTERS"

# shellcheck disable=SC2086
tap_xy $PLAY
sleep 4
wait_state PLAYING
notes=$(adb shell dumpsys notification --noredact)
grep -q "pkg=$PKG" <<<"$notes" || fail "no playback notification"
shot 07-playing
crashed play

# ---- 5. 10 seconds a tap: a double tap is 20, a triple tap 30 ------------------
# shellcheck disable=SC2086
tap_xy $PLAY
sleep 1.5
wait_state PAUSED
P0=$(pb pos)
# shellcheck disable=SC2086
{ tap_xy $FWD; tap_xy $FWD; tap_xy $FWD; }
sleep 1.5
P1=$(pb pos)
between "three taps forward" $((P1 - P0)) 29000 31000
# shellcheck disable=SC2086
tap_xy $BACK
sleep 1.5
P2=$(pb pos)
between "one tap back" $((P1 - P2)) 9000 11000
# Seven more taps run 70 s on, off the end of the 90 s Chapter 1 and into Chapter 2.
for i in 1 2 3 4 5 6 7; do
    # shellcheck disable=SC2086
    tap_xy $FWD
done
sleep 2.5
[ "$(pb title)" = "Chapter 2" ] || fail "70 s on from $P2 ms should be in Chapter 2, not '$(pb title)'"
C2=$((P2 + 70000 - 90000))
between "position in Chapter 2" "$(pb pos)" $((C2 - 1500)) $((C2 + 1500))
expect 'text="Chapter 2"'
shot 08-into-chapter-2

# ---- 6. chapters in number order, a jump, and undo ------------------------------
# shellcheck disable=SC2086
tap_xy $CHAPTERS
sleep 2
shot 09-chapters
x=$(ui)
SEQ=$(grep -o 'text="[^"]*"' <<<"$x" | tr '\n' ' ')
echo "chapter sheet: $SEQ"
case "$SEQ" in *'text="1" text="Chapter 1"'*) ;; *) fail "Chapter 1 is not first";; esac
case "$SEQ" in *'text="3" text="Chapter 10"'*) ;; *) fail "Chapter 10 is not third";; esac
tap 'text="Chapter 10"' "" 3
[ "$(pb title)" = "Chapter 10" ] || fail "picking Chapter 10 played '$(pb title)'"
wait_state PLAYING
# shellcheck disable=SC2086
tap_xy $PLAY
sleep 1
wait_state PAUSED
shot 10-jumped
tap 'text="Undo' "" 2.5
[ "$(pb title)" = "Chapter 2" ] || fail "undo went to '$(pb title)', not back to Chapter 2"
between "position after undo" "$(pb pos)" $((C2 - 1500)) $((C2 + 1500))
shot 11-undone

# ---- 7. speed ------------------------------------------------------------------
# shellcheck disable=SC2086
tap_xy $SPEED
sleep 2
shot 12-speed
tap 'text="1.5×"' "" 1.5
adb shell input keyevent 4
sleep 1.5
expect 'text="1.5×"'

# ---- 8. sleep timer, set and cancelled -----------------------------------------
# shellcheck disable=SC2086
tap_xy $SLEEP
sleep 2
shot 13-sleep
EOC=$(locate 'text="End of this chapter"')
[ -n "$EOC" ] || fail "no end-of-chapter option"
tap 'text="5 min"' "" 1.5
expect 'text="5 min"'
shot 14-sleep-set
# shellcheck disable=SC2086
tap_xy $SLEEP
sleep 2
tap 'text="Turn off"' "" 1.5
expect 'text="Sleep"'
crashed sheets

# ---- 9. rotation ---------------------------------------------------------------
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 4
shot 15-landscape
crashed rotate
adb shell settings put system user_rotation 0
sleep 3
crashed rotate-back

# ---- 10. on the home screen, then with the screen off ----------------------------
# shellcheck disable=SC2086
tap_xy $PLAY
sleep 3
wait_state PLAYING
[ "$(pb speed)" = "1.5" ] || fail "playing at $(pb speed)x, expected 1.5x"
adb shell input keyevent 3
sleep 6
wait_state PLAYING 3
shot 16-home
A=$(pb pos)
adb shell input keyevent 26
sleep 8
wait_state PLAYING 3
B=$(pb pos)
[ "$B" -gt "$A" ] || fail "the position stood still with the screen off ($A, then $B)"
echo "screen off: position $A -> $B"
adb shell input keyevent 224
sleep 1
adb shell wm dismiss-keyguard || true
sleep 2
crashed background

# ---- 11. killed mid-chapter, back at the same place -------------------------------
TITLE=$(pb title)
BEFORE=$(pb pos)
PID=$(adb shell pidof "$PKG" | tr -d '\r' || true)
[ -n "$PID" ] || fail "BookJam is not running"
adb shell kill -9 "$PID"
sleep 3
[ -z "$(adb shell pidof "$PKG" | tr -d '\r' || true)" ] || fail "BookJam survived kill -9"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 5
shot 17-after-kill
crashed relaunch
expect 'content-desc="Now playing"'
expect "text=\"$TITLE\""
tap 'content-desc="Now playing"' "" 4
wait_state PAUSED
[ "$(pb title)" = "$TITLE" ] || fail "came back in '$(pb title)', not '$TITLE'"
between "position after kill -9, was $BEFORE" "$(pb pos)" $((BEFORE - 6000)) $((BEFORE + 12000))
shot 18-resumed

# ---- 12. "end of this chapter" stops, saves and closes the app ----------------------
adb shell input keyevent 4
sleep 2
tap 'text="Short Book"' "" 4
expect 'text="01 Opening"'
# shellcheck disable=SC2086
tap_xy $PLAY
sleep 2
wait_state PLAYING
# shellcheck disable=SC2086
tap_xy $SLEEP
sleep 2
# shellcheck disable=SC2086
tap_xy $EOC
sleep 1
shot 19-end-of-chapter-set
for i in $(seq 1 30); do
    s=$(pb state)
    if [ "$s" != "PLAYING" ] && [ "$s" != "BUFFERING" ]; then break; fi
    sleep 1
done
sleep 2
crashed sleep-timer
FOCUS=$(adb shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r' || true)
echo "focus after the timer: $FOCUS"
case "$FOCUS" in *"$PKG"*) fail "BookJam is still on screen after the sleep timer";; esac
notes=$(adb shell dumpsys notification --noredact)
if grep -q "pkg=$PKG" <<<"$notes"; then fail "the notification outlived the sleep timer"; fi
shot 20-closed-by-timer
adb shell am start -W -n "$PKG/.MainActivity"
sleep 4
expect 'text="02 Closing"'
shot 21-next-time
crashed end

echo "picker, library, playback, skips, chapters, undo, speed, sleep timer, background,"
echo "kill -9 and the sleep timer closing the app all work"
