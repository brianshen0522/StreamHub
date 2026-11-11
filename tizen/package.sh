#!/usr/bin/env bash
# Build a signed Tizen .wgt: ./build.sh release → stage dist/ + config.xml +
# icon.png → `tizen package -t wgt -s <profile>` in the Tizen CLI image.
#
#   ./package.sh                        # throwaway author cert (created on first run)
#   STREAMHUB_SIGN_PROFILE=samsung ./package.sh
#                                       # a profile already in .sign/tizen-studio-data
#   STREAMHUB_AUTHOR_P12=.sign/samsung/author.p12 STREAMHUB_AUTHOR_PASSWORD_FILE=.sign/samsung/author.password \
#   STREAMHUB_DISTRIBUTOR_P12=.sign/samsung/distributor.p12 STREAMHUB_DISTRIBUTOR_PASSWORD_FILE=.sign/samsung/distributor.password \
#   STREAMHUB_SIGN_PROFILE=samsung ./package.sh
#                                       # (re)create profile "samsung" from those files, then sign
#
# Output: tizen/StreamHub.wgt (gitignored). Other env:
#   SKIP_BUILD=1          reuse the current dist/ (must be a release build to ship)
#   STAGE_ONLY=1          stop after staging (target/wgt-stage); qa/run.sh uses
#                         this to load the widget layout over file://
#   STREAMHUB_API_BASE    compiled-in server, passed through to build.sh
#
# Everything secret lives in tizen/.sign/ (gitignored): the Tizen Studio data
# dir (keystore, profiles.xml) is mounted from .sign/tizen-studio-data.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sign="$here/.sign"
image=streamhub-tizen-cli
profile="${STREAMHUB_SIGN_PROFILE:-streamhub-dev}"
out="$here/StreamHub.wgt"

mkdir -p "$sign/tizen-studio-data" && chmod 700 "$sign"

# 1. Build.
if [[ -z "${SKIP_BUILD:-}" ]]; then
  "$here/build.sh" release
fi
[[ -f "$here/dist/index.html" ]] || { echo "dist/index.html missing" >&2; exit 1; }

# 2. Stage. A widget loads from file://…/res/wgt/, so root-absolute URLs
#    (Trunk's default public_url "/") point at the filesystem root. Rewrite
#    them to ./ and refuse to package if any remain.
# Kept after the run (target/ is gitignored) so it can be inspected or loaded
# over file:// by qa/specs/05-file-url.spec.js.
stage="$here/target/wgt-stage"
rm -rf "$stage" && mkdir -p "$stage"
cp -r "$here/dist/." "$stage/"
cp "$here/package/config.xml" "$here/package/icon.png" "$stage/"
sed -i -E "s#(href|src)=\"/([^/\"])#\1=\"./\2#g; s#'/(streamhub-[^']+)'#'./\1'#g" "$stage/index.html"
if grep -nE "(href|src)=\"/[^/]|'/streamhub-" "$stage/index.html"; then
  echo "index.html still has root-absolute URLs (above); the widget would not load" >&2
  exit 1
fi
# Tizen Studio leaves these in projects; they must not ship.
rm -f "$stage/.project" "$stage/.tproject" "$stage"/*.wgt
if [[ -n "${STAGE_ONLY:-}" ]]; then echo "staged: $stage"; exit 0; fi

# 3. Sign + package in the CLI image.
# Refresh the image, but fall back to a cached one when the build can't reach
# the registry or a download host.
docker build -q -t "$image" -f "$here/build/Dockerfile.tizen" "$here/build" >/dev/null \
  || docker image inspect "$image" >/dev/null 2>&1 \
  || { echo "cannot build $image and no cached copy" >&2; exit 1; }

docker run --rm -u "$(id -u):$(id -g)" \
  -e PROFILE="$profile" \
  -e AUTHOR_P12="${STREAMHUB_AUTHOR_P12:-}" -e AUTHOR_PWF="${STREAMHUB_AUTHOR_PASSWORD_FILE:-}" \
  -e DIST_P12="${STREAMHUB_DISTRIBUTOR_P12:-}" -e DIST_PWF="${STREAMHUB_DISTRIBUTOR_PASSWORD_FILE:-}" \
  -v "$sign/tizen-studio-data:/home/tizen/tizen-studio-data" \
  -v "$here:/tizen:ro" \
  -v "$stage:/stage" \
  "$image" bash -euo pipefail -c '
    data=/home/tizen/tizen-studio-data
    profiles=$data/profile/profiles.xml
    in_tizen() { case "$1" in /*) echo "$1" ;; *) echo "/tizen/$1" ;; esac; }
    has_profile() { [[ -f $profiles ]] && grep -q "name=\"$1\"" "$profiles"; }
    # Headless, the CLI has no keyring: `security-profiles add` records a path
    # to a .pwd file it never writes, and packaging then fails with "Invaild
    # password". An inline password attribute is accepted instead, but it is
    # read through org.tizen.common.util.CipherUtil.getDecryptedString — so
    # encrypt with CipherUtil from the CLI jars and write that (profiles.xml stays
    # in .sign/, mode 600).
    tz_encrypt() {  # reads the plaintext from $PLAIN
      local lib=/home/tizen/tizen-studio/tools/ide/lib-ncli cp
      cp=$(ls $lib/*.jar | tr "\n" ":")
      echo "print(\"ENC=\" + Java.type(\"org.tizen.common.util.CipherUtil\").getEncryptedString(java.lang.System.getenv(\"PLAIN\")))" > /tmp/enc.js
      /home/tizen/tizen-studio/jdk/bin/jrunscript -Dlog4j2.level=OFF -Dlog4j2.statusLoggerLevel=OFF -cp "$cp" /tmp/enc.js 2>/dev/null \
        | sed -n "s/^ENC=//p"
    }
    inline_passwords() {  # profile author-pw [distributor-pw]
      local a d
      a=$(PLAIN="$2" tz_encrypt); d=$(PLAIN="${3:-tizenpkcs12passfordsigner}" tz_encrypt)
      [[ -n $a && -n $d ]] || { echo "could not encrypt signing passwords" >&2; exit 1; }
      P="$1" A="$a" D="$d" python3 -I - "$profiles" <<"PY"
import os, sys, xml.etree.ElementTree as ET
path = sys.argv[1]; tree = ET.parse(path)
for prof in tree.getroot().iter("profile"):
    if prof.get("name") != os.environ["P"]: continue
    for item in prof.iter("profileitem"):
        kind = item.get("distributor")
        if kind == "0": item.set("password", os.environ["A"])
        elif kind == "1" and item.get("key"): item.set("password", os.environ["D"])
tree.write(path, encoding="UTF-8", xml_declaration=True)
PY
      chmod 600 "$profiles"
    }

    if [[ -n "$AUTHOR_P12" ]]; then
      # Explicit certificate files (e.g. the Samsung pair from Certificate
      # Manager). Copied into the keystore because the CLI writes its own
      # encrypted <name>.pwd next to each .p12, and /tizen is read-only.
      ks=$data/keystore/$PROFILE; mkdir -p "$ks"; umask 077
      cp "$(in_tizen "$AUTHOR_P12")" "$ks/author.p12"
      apw="$(cat "$(in_tizen "$AUTHOR_PWF")")"; dpw=""
      args=(-n "$PROFILE" -a "$ks/author.p12" -p "$apw")
      if [[ -n "$DIST_P12" ]]; then
        cp "$(in_tizen "$DIST_P12")" "$ks/distributor.p12"
        dpw="$(cat "$(in_tizen "$DIST_PWF")")"
        args+=(-d "$ks/distributor.p12" -dp "$dpw")
      fi
      has_profile "$PROFILE" && tizen security-profiles remove -n "$PROFILE" >/dev/null
      tizen security-profiles add "${args[@]}" >/dev/null
      inline_passwords "$PROFILE" "$apw" "$dpw"
    elif ! has_profile "$PROFILE"; then
      if [[ "$PROFILE" != streamhub-dev ]]; then
        echo "security profile \"$PROFILE\" not found in .sign/tizen-studio-data" >&2; exit 1
      fi
      # Throwaway author certificate: proves the pipeline, signs with the
      # Tizen public distributor. A Samsung TV will NOT install this — see README.
      mkdir -p $data/keystore/author
      pw=$(head -c 24 /dev/urandom | base64 | tr -dc A-Za-z0-9 | head -c 20)
      # Plaintext copy for us, deliberately not *.pwd: the CLI stores its own
      # encrypted password as <p12 name>.pwd and would read ours as that.
      umask 077; printf %s "$pw" > $data/keystore/author/streamhub-dev.password
      tizen certificate -a streamhub-dev -p "$pw" -c TW -n "StreamHub QA" -f streamhub-dev \
        -- $data/keystore/author >/dev/null
      tizen security-profiles add -n streamhub-dev -a $data/keystore/author/streamhub-dev.p12 -p "$pw" >/dev/null
      inline_passwords streamhub-dev "$pw"
      echo "created throwaway security profile streamhub-dev"
    fi

    tizen package -t wgt -s "$PROFILE" -- /stage
  '

wgt="$(ls -1 "$stage"/*.wgt | head -1)"
[[ -f "$wgt" ]] || { echo "tizen package produced no .wgt" >&2; exit 1; }
mv "$wgt" "$out"
echo "signed with profile \"$profile\": $out"
unzip -l "$out" | grep -E "signature|config.xml|index.html" || true
