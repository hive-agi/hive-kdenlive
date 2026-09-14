#!/usr/bin/env bash
# Verify the native MLT bridge against an oracle that is NOT MLT.
#
# native_probe.cljrs renders through libmlt and checks the frame counts MLT
# reports back. That proves MLT agrees with itself. This script then reads the
# rendered file with ffprobe/ffmpeg and checks what a viewer would see:
#
#   frames and size   ffprobe -count_frames: 75 frames at 320x240
#   pixels            frame 5 red, frame 30 black (the blank), frame 60 green
#
# The pixel check is load-bearing, not decoration. A render whose frame counts
# were right while the colours were wrong has already happened here: a colour
# resource spelled "color:#ff0000" under mlt_service=color rendered near-black,
# and a blank with no background track rendered white. Counting frames passed.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLJRS="${CLJRS:-/home/leibniz/PP/clojurust/target/debug/cljrs}"
out="${1:-$(mktemp -d)}"
mkdir -p "$out"

for tool in ffprobe ffmpeg; do
  command -v "$tool" >/dev/null || { echo "no $tool on PATH; the independent oracle needs it"; exit 2; }
done
if [[ ! -x "$CLJRS" ]]; then
  echo "no cljrs at $CLJRS; set CLJRS to a binary built in the SAME cargo profile as native/rust"
  exit 2
fi

fail=0

# Emission before rendering: the byte oracle compares the document this host
# emits against the JVM's expected.mlt. It needs no native library, so a
# divergence shows up here in seconds instead of as a wrong render later.
echo "== 0. the document this host emits, byte for byte against the JVM's"
if ! (cd "$here/.." && "$CLJRS" run dev/oracle.cljrs --src-path src --src-path test); then
  echo "  FAIL the byte oracle"
  exit 1
fi

echo "== 1. the probe, on cljrs (MLT checking MLT)"
cd "$here"
if ! "$CLJRS" run src/hive_kdenlive/native_probe.cljrs -- "$out"; then
  echo "  FAIL the probe"
  exit 1
fi

video="$out/hive-kdenlive-probe.mkv"

echo "== 2. the rendered file, read by ffprobe"
got=$(ffprobe -v error -count_frames -select_streams v:0 \
        -show_entries stream=width,height,nb_read_frames -of csv=p=0 "$video")
if [[ "$got" == "320,240,75" ]]; then
  echo "  OK   width,height,frames = $got"
else
  echo "  FAIL width,height,frames: expected 320,240,75 got $got"
  fail=1
fi

echo "== 3. what a viewer sees, sampled by ffmpeg"
pixel() { # frame -> "r g b" of the frame averaged to one pixel
  ffmpeg -v error -y -i "$video" -vf "select=eq(n\,$1),scale=1:1" -fps_mode passthrough \
         -frames:v 1 -f rawvideo -pix_fmt rgb24 - | od -An -tu1 | xargs
}
near() { # "r g b" expected-r expected-g expected-b: each channel within 16
  local -a p=($1)
  local dr=$(( p[0] - $2 )) dg=$(( p[1] - $3 )) db=$(( p[2] - $4 ))
  (( ${dr#-} <= 16 && ${dg#-} <= 16 && ${db#-} <= 16 ))
}
check_pixel() { # label frame r g b
  local p; p=$(pixel "$2")
  if near "$p" "$3" "$4" "$5"; then
    echo "  OK   $1 (frame $2) = $p"
  else
    echo "  FAIL $1 (frame $2): expected ~$3 $4 $5 got $p"
    fail=1
  fi
}
check_pixel "red entry"   5   255 0   0
check_pixel "blank"       30  0   0   0
check_pixel "green clip"  60  0   255 0

if (( fail )); then
  echo "native bridge DISAGREES with the independent oracle"
  exit 1
fi
echo "native bridge agrees with ffprobe and ffmpeg"
