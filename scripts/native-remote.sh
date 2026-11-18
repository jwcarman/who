#!/usr/bin/env bash
#
# Runs who's native-image tests on a Linux build host over ssh.
#
# The committed HEAD is sent as a git bundle (nothing is pushed), checked out on the host, and
# each native app is compiled with GraalVM and tested there, one after another.
#
# Usage: scripts/native-remote.sh [app...]     e.g. scripts/native-remote.sh native-passkey
#        With no apps, every who-native-tests/native-* app except native-test-support runs.
#
# Environment:
#   NATIVE_HOST       required: the ssh host to build on
#   NATIVE_JAVA_HOME  the GraalVM (or Liberica NIK) home on the host; default: the host's JAVA_HOME
#   NATIVE_DIR        the working copy on the host; default: ~/who-native
#
# The host needs git, Docker (the tests start PostgreSQL), gcc and zlib headers. Logs stay on the
# host as $NATIVE_DIR/<app>.log. Exits non-zero when any app fails.

set -euo pipefail

: "${NATIVE_HOST:?set NATIVE_HOST to the ssh host to build on}"
NATIVE_DIR="${NATIVE_DIR:-~/who-native}"
NATIVE_JAVA_HOME="${NATIVE_JAVA_HOME:-}"

cd "$(git rev-parse --show-toplevel)"

if [[ $# -gt 0 ]]; then
  apps=("$@")
else
  apps=()
  for dir in who-native-tests/native-*/; do
    app="$(basename "$dir")"
    [[ "$app" == native-test-support ]] || apps+=("$app")
  done
fi
for app in "${apps[@]}"; do
  [[ -d "who-native-tests/$app" ]] || { echo "no such app: $app" >&2; exit 2; }
done

if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
  echo "warning: uncommitted changes are not sent; the host builds $(git rev-parse --short HEAD)" >&2
fi

bundle="$(mktemp -t who-native.XXXXXX)"
trap 'rm -f "$bundle"' EXIT
git bundle create --quiet "$bundle" HEAD
scp -q "$bundle" "$NATIVE_HOST:who-native.bundle"

echo "building $(git rev-parse --short HEAD) on the build host: ${apps[*]}"

# The remote script takes its settings as arguments, so nothing local is expanded inside it.
ssh -o ServerAliveInterval=60 "$NATIVE_HOST" bash -s -- \
  "$NATIVE_DIR" "$NATIVE_JAVA_HOME" "${apps[@]}" <<'REMOTE'
set -uo pipefail
dir="${1/#\~/$HOME}"; java_home="$2"; shift 2
if [[ -n "$java_home" ]]; then
  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
[[ -n "${JAVA_HOME:-}" ]] || { echo "JAVA_HOME is not set on the host; set NATIVE_JAVA_HOME" >&2; exit 2; }
if [[ ! -d "$dir/.git" ]]; then
  git clone --quiet ~/who-native.bundle "$dir"
fi
cd "$dir"
git fetch --quiet ~/who-native.bundle HEAD
git checkout --quiet --force --detach FETCH_HEAD
failed=0
for app in "$@"; do
  start=$SECONDS
  if ./mvnw -B -Pnative-tests -Dnative verify -pl "who-native-tests/$app" -am \
      -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false > "$app.log" 2>&1; then
    result=passed
  else
    result=FAILED
    failed=1
  fi
  tests="$(grep -E 'Tests run:.*Failures' "$app.log" | tail -1 | sed 's/^\[[A-Z]*\] *//')"
  echo "$app: $result in $((SECONDS - start))s ${tests:+($tests)}"
done
exit $failed
REMOTE
