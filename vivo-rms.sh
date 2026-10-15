#!/usr/bin/env bash
# vivo-rms.sh — rootless high refresh rate (144 Hz and up) for vivo phones
#
# vivo's framework (com.vivo.rms.common.RefreshRateRequester) drives the panel
# rate through the "rms" binder [com.vivo.rms.IRM] with raw Parcels and no
# interface token. `service call` always writes one, so the args land shifted;
# RmsVote.dex sends them the framework's way through app_process instead.
#
# usage:  vivo-rms.sh build                  javac + d8 -> RmsVote.dex
#         vivo-rms.sh get                    current rate (tx 26)
#         vivo-rms.sh set  <fps> [opts]      vote; stays until released/reboot
#         vivo-rms.sh hold <fps> [opts]      vote and keep it until Ctrl-C
#         vivo-rms.sh release 0 900001       withdraw a `set` vote
#         vivo-rms.sh modes                  refresh rates the panel offers
#         vivo-rms.sh reason                 which request the vendor applies
# opts:   --caller <pid> --scene <name> --prio <n> --dur <n> --flags <n>
#         --display <n> --handle <n> --v1   (defaults: see RmsVote.java)
# An app that asks for its own display mode still wins while it is in front.

set -u
# Git Bash would rewrite /data/local/tmp into a Windows path
export MSYS_NO_PATHCONV=1

DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_DEX="$DIR/RmsVote.dex"
DEX=/data/local/tmp/RmsVote.dex

die() { echo "error: $*" >&2; exit 1; }

build() {
  command -v javac >/dev/null || die "javac not found (apt install openjdk-17-jdk-headless)"
  command -v d8 >/dev/null || die "d8 not found (add Android SDK build-tools to PATH)"
  local tmp; tmp=$(mktemp -d)
  javac -nowarn --release 8 -d "$tmp" "$DIR/RmsVote.java" || die "javac failed"
  d8 --release --min-api 26 --output "$tmp" "$tmp"/RmsVote*.class || die "d8 failed"
  mv "$tmp/classes.dex" "$LOCAL_DEX"
  rm -rf "$tmp"
  echo "built $LOCAL_DEX"
}

pick_device() {
  command -v adb >/dev/null || die "adb not found"
  local d
  d=$(adb devices | awk '/\tdevice$/{print $1; exit}')
  [ -n "$d" ] || die "no adb device connected"
  ADB="adb -s $d"
}

push_dex() {
  [ -f "$LOCAL_DEX" ] || build
  # always push: a stale dex on the phone would run the old argument layout
  $ADB push "$LOCAL_DEX" $DEX >/dev/null || die "push failed"
}

case "${1:-}" in
  ""|-h|--help) sed -n '2,18p' "$0"; exit 0 ;;
  build) build; exit 0 ;;
  modes)
    pick_device
    $ADB shell "dumpsys display | grep -oE 'fps=[0-9.]+' | sort -u"
    echo "private fps: $($ADB shell getprop persist.sys.private_fps)"
    echo "public max:  $($ADB shell getprop persist.vivo.phone.fps_max)"
    echo "rrm version: $($ADB shell getprop debug.rms.refresh_rate_adjuster_version)"
    exit 0 ;;
  reason)
    pick_device
    $ADB shell settings get secure vivo_rms_active_request_reason
    exit 0 ;;
esac

pick_device
push_dex
# -t: Ctrl-C reaches app_process, so `hold` runs its shutdown hook and releases
exec $ADB shell -t CLASSPATH=$DEX app_process / RmsVote "$@"
