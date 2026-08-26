#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

app_version="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)"
jar_path="${1:-target/nytweetdeck-${app_version}.jar}"
chrome_binary="${CHROME_BIN:-google-chrome}"
profile_directory="$(mktemp -d)"
java_pid=""
chrome_pid=""

cleanup() {
  if [[ -n "$chrome_pid" ]]; then kill "$chrome_pid" 2>/dev/null || true; fi
  if [[ -n "$java_pid" ]]; then kill "$java_pid" 2>/dev/null || true; fi
  rm -rf -- "$profile_directory"
}
trap cleanup EXIT

java -jar "$jar_path" > target/ui-server.log 2> target/ui-server-error.log &
java_pid=$!

ready=false
for _ in {1..80}; do
  if curl --fail --silent --show-error http://127.0.0.1:18080/api/v1/system/status >/dev/null; then
    ready=true
    break
  fi
  sleep 0.25
done
if [[ "$ready" != true ]]; then
  echo "NyTweetDeckが20秒以内に起動しませんでした。" >&2
  exit 1
fi

"$chrome_binary" \
  --headless=new \
  --disable-gpu \
  --no-first-run \
  --remote-debugging-port=9222 \
  "--user-data-dir=$profile_directory" \
  about:blank >/dev/null 2>&1 &
chrome_pid=$!
sleep 2

(cd frontend && bun run verify:ui)
