#!/bin/sh
# Requires Linux, JDK17+, TigerVNC, xauth, xdpyinfo, dbus-run-session, Python3/libX11.
# LYRA_X11_CLASSPATH must contain the built library's classes.jar and kotlin-stdlib.jar.
set -eu
: "${LYRA_X11_CLASSPATH:?Set the module and Kotlin runtime classpath}"
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d /tmp/lyra-x11-smoke.XXXXXXXX)
token=$(cat /proc/sys/kernel/random/uuid)
bridge=/tmp/lyra-rfb-$token
display=${LYRA_X11_TEST_DISPLAY:-987}
supervisor=''
group=''
cleanup() {
    trap - EXIT HUP INT TERM
    if [ -n "$group" ]; then python3 -c 'import os,signal,sys; os.killpg(int(sys.argv[1]), signal.SIGTERM)' "$group" 2>/dev/null || true
    elif [ -n "$supervisor" ]; then kill "$supervisor" 2>/dev/null || true; fi
    [ -z "$supervisor" ] || wait "$supervisor" 2>/dev/null || true
    rm -f -- "$bridge/rfb"
    rmdir -- "$bridge" 2>/dev/null || true
    rm -rf -- "/tmp/lyra-x11-$token" "$work"
}
trap cleanup EXIT HUP INT TERM
mkdir -m 700 "$bridge"
java --class-path "$LYRA_X11_CLASSPATH" "$base/TigerVncSmoke.java" --emit "$work/bootstrap.sh" "$token" "$base/paint-smoke.py" "$display"
sh -n "$work/bootstrap.sh"
socket=$bridge/rfb
if [ -n "${LYRA_X11_EXEC_WRAPPER:-}" ]; then
    mkdir -m 700 "$work/bridge"
    export LYRA_X11_HOST_BRIDGE="$work/bridge" LYRA_X11_GUEST_BRIDGE="$bridge"
    setsid sh "$LYRA_X11_EXEC_WRAPPER" sh "$work/bootstrap.sh" >"$work/server.log" 2>&1 &
    group=$!
    socket=$work/bridge/rfb
else
    setsid sh "$work/bootstrap.sh" >"$work/server.log" 2>&1 &
    group=$!
fi
supervisor=$!
count=0
until [ -S "$socket" ]; do
    kill -0 "$supervisor" 2>/dev/null || { cat "$work/server.log"; exit 1; }
    count=$((count + 1)); [ "$count" -lt 100 ] || { cat "$work/server.log"; exit 1; }
    sleep 0.1
done
[ "$(stat -c %a "$socket")" = 600 ] || { echo 'Incorrect RFB socket permissions'; exit 1; }
if ! java --class-path "$LYRA_X11_CLASSPATH" "$base/TigerVncSmoke.java" "$socket"; then cat "$work/server.log"; exit 1; fi
password_tool=$(command -v tigervncpasswd || command -v vncpasswd)
native=$(printf 'password\n' | "$password_tool" -f | od -An -tx1 | tr -d ' \n')
jvm=$(java --class-path "$LYRA_X11_CLASSPATH" "$base/TigerVncSmoke.java" --password-vector)
[ "$native" = "$jvm" ] || { echo "Password-file compatibility mismatch: native=$native jvm=$jvm"; exit 1; }
echo "TIGERVNC_PASSWORD_FORMAT_OK"
