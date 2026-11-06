#!/bin/sh
# Linux/JDK17+, pulseaudio/pipewire and pactl/paplay. Never installs or edits system configuration.
# Optional LYRA_MEDIA_EXEC_WRAPPER runs commands with the private media directory mounted.
set -eu
: "${LYRA_X11_CLASSPATH:?Set module classes.jar and kotlin-stdlib.jar}"
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
work=$(mktemp -d /tmp/lyra-audio-smoke.XXXXXXXX)
supervisor=''; reader=''
cleanup() {
    trap - EXIT HUP INT TERM
    [ -z "$supervisor" ] || kill "$supervisor" 2>/dev/null || true
    [ -z "$supervisor" ] || wait "$supervisor" 2>/dev/null || true
    [ -z "$reader" ] || kill "$reader" 2>/dev/null || true
    [ -z "$reader" ] || wait "$reader" 2>/dev/null || true
    rm -rf -- "$work"
}
trap cleanup EXIT HUP INT TERM
python3 - "$work/tone.pcm" <<'PY'
import math,struct,sys
with open(sys.argv[1], 'wb') as output:
    for i in range(48000):
        sample = int(12000 * math.sin(2 * math.pi * 440 * i / 48000))
        output.write(struct.pack('<hh', sample, sample))
PY
for backend in ${LYRA_MEDIA_BACKENDS:-PULSEAUDIO PIPEWIRE}; do
    mkdir -m 700 "$work/media-$backend" "$work/state-$backend"
    mkfifo -m 600 "$work/media-$backend/audio.pcm"
    export LYRA_MEDIA_HOST_BRIDGE="$work/media-$backend" LYRA_MEDIA_GUEST_BRIDGE="$work/mount-$backend"
    mkdir -m 700 "$LYRA_MEDIA_GUEST_BRIDGE"
    java --class-path "$LYRA_X11_CLASSPATH" "$base/MediaBootstrapSmoke.java" "$work/bootstrap.sh" "$work/state-$backend" "$backend"
    replacement=$LYRA_MEDIA_GUEST_BRIDGE
    if [ -z "${LYRA_MEDIA_EXEC_WRAPPER:-}" ]; then replacement=$LYRA_MEDIA_HOST_BRIDGE; fi
    python3 - "$work/bootstrap.sh" "$replacement" <<'PY'
import pathlib,sys
p = pathlib.Path(sys.argv[1]); p.write_text(p.read_text().replace('/run/lyra/0123456789abcdef', sys.argv[2]))
PY
    sh -n "$work/bootstrap.sh"
    python3 "$base/read-pcm-smoke.py" "$work/media-$backend/audio.pcm" "$work/result-$backend" "$backend" &
    reader=$!
    if [ -n "${LYRA_MEDIA_EXEC_WRAPPER:-}" ]; then
        timeout -k 5 60 sh "$LYRA_MEDIA_EXEC_WRAPPER" sh "$work/bootstrap.sh" "$work/tone.pcm" >"$work/session-$backend.log" 2>&1 &
    else
        timeout -k 5 60 sh "$work/bootstrap.sh" "$work/tone.pcm" >"$work/session-$backend.log" 2>&1 &
    fi
    supervisor=$!
    if ! wait "$supervisor"; then cat "$work/session-$backend.log" "$work/media-$backend/audio.log"; exit 1; fi
    supervisor=''
    if ! wait "$reader"; then cat "$work/session-$backend.log" "$work/media-$backend/audio.log"; exit 1; fi
    reader=''
    cat "$work/result-$backend"
done
