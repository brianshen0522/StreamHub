#!/usr/bin/env bash
# Install, launch and debug StreamHub on a Samsung TV / Smart Monitor in
# Developer Mode, using sdb + the Tizen CLI from the streamhub-tizen-cli image.
#
#   ./install.sh <tv-ip>               connect, install StreamHub.wgt, launch
#   ./install.sh <tv-ip> run           launch only
#   ./install.sh <tv-ip> debug         launch in debug mode + forward the Web
#                                      Inspector to http://localhost:9222 (stays
#                                      in the foreground; Ctrl-C to stop)
#   ./install.sh <tv-ip> duid          print the DUID (for the Samsung distributor cert)
#   ./install.sh <tv-ip> info          sdb devices + platform capability dump
#   ./install.sh <tv-ip> uninstall
#   ./install.sh <tv-ip> shell         interactive shell in the CLI container
#
# Env: WGT (default tizen/StreamHub.wgt), INSPECTOR_PORT (default 9222).
#
# The TV only accepts sdb from the IP entered in its Developer Mode dialog, so
# run this on that machine. Docker uses host networking so sdb's connection
# and the inspector forward are the host's own.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
ip="${1:?usage: ./install.sh <tv-ip> [install|run|debug|duid|info|uninstall|shell]}"
cmd="${2:-install}"
appid=StrmHubTV1.StreamHub
pkgid=StrmHubTV1
wgt="${WGT:-$here/StreamHub.wgt}"
port="${INSPECTOR_PORT:-9222}"

docker build -q -t streamhub-tizen-cli -f "$here/build/Dockerfile.tizen" "$here/build" >/dev/null
tty=(-i); [[ -t 0 ]] && tty=(-it)

docker run --rm "${tty[@]}" --network host -u "$(id -u):$(id -g)" \
  -e IP="$ip" -e CMD="$cmd" -e APPID="$appid" -e PKGID="$pkgid" -e PORT="$port" \
  -e WGT_NAME="$(basename "$wgt")" \
  -v "$(dirname "$(readlink -f "$wgt")"):/wgt:ro" \
  -v "$here/.sign/tizen-studio-data:/home/tizen/tizen-studio-data" \
  streamhub-tizen-cli bash -euo pipefail -c '
    serial="$IP:26101"
    sdb connect "$IP" >/dev/null || true
    if ! sdb devices | grep -q "^$serial"; then
      echo "sdb could not reach $serial. Check: Developer Mode on, Host PC IP = this machine, TV restarted after enabling it." >&2
      sdb devices >&2; exit 1
    fi
    case "$CMD" in
      install)
        [[ -f /wgt/$WGT_NAME ]] || { echo "no /wgt/$WGT_NAME — run ./package.sh first" >&2; exit 1; }
        tizen install -n "$WGT_NAME" -s "$serial" -- /wgt
        tizen run -p "$APPID" -s "$serial" ;;
      run)       tizen run -p "$APPID" -s "$serial" ;;
      uninstall) tizen uninstall -p "$PKGID" -s "$serial" ;;
      duid)      sdb -s "$serial" shell 0 getduid ;;
      info)      sdb devices; sdb -s "$serial" capability ;;
      debug)
        # "0 debug" starts the app with the inspector on; it prints the port.
        out=$(sdb -s "$serial" shell 0 debug "$APPID" 2>&1); echo "$out"
        remote=$(echo "$out" | grep -oE "port: *[0-9]+" | grep -oE "[0-9]+" | tail -1)
        [[ -n "$remote" ]] || { echo "no debug port in the output above" >&2; exit 1; }
        sdb -s "$serial" forward "tcp:$PORT" "tcp:$remote"
        echo
        echo "Web Inspector: open http://localhost:$PORT in Chrome (or chrome://inspect →"
        echo "Configure → localhost:$PORT). Ctrl-C ends the forward."
        trap "sdb -s $serial forward --remove tcp:$PORT >/dev/null 2>&1 || true" EXIT
        while sleep 3600; do :; done ;;
      shell) bash ;;
      *) echo "unknown command: $CMD" >&2; exit 2 ;;
    esac
  '
