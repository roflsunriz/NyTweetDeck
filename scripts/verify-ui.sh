#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repository_root"

app_version="$(mvn -q help:evaluate -Dexpression=project.version -DforceStdout)"
jar_path="${1:-target/nytweetdeck-${app_version}.jar}"
chrome_binary="${CHROME_BIN:-}"
profile_directory="$(mktemp -d)"
java_pid=""
chrome_pid=""

if [[ -n "$chrome_binary" ]]; then
  resolved_chrome="$(command -v "$chrome_binary" 2>/dev/null || true)"
  if [[ -n "$resolved_chrome" ]]; then chrome_binary="$resolved_chrome"; fi
else
  for candidate in google-chrome google-chrome-stable chromium chromium-browser; do
    if command -v "$candidate" >/dev/null 2>&1; then
      chrome_binary="$(command -v "$candidate")"
      break
    fi
  done
fi
if [[ -z "$chrome_binary" || ! -x "$chrome_binary" ]]; then
  echo "Chrome/Chromiumの実行ファイルが見つかりません。" >&2
  exit 1
fi

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
  if ! kill -0 "$java_pid" 2>/dev/null; then
    echo "NyTweetDeckが起動前に終了しました。" >&2
    cat target/ui-server-error.log >&2 || true
    exit 1
  fi
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
  --disable-dev-shm-usage \
  --no-first-run \
  --remote-debugging-address=127.0.0.1 \
  --remote-debugging-port=9222 \
  "--user-data-dir=$profile_directory" \
  about:blank > target/ui-chrome.log 2>&1 &
chrome_pid=$!

cdp_ready=false
for _ in {1..40}; do
  if ! kill -0 "$chrome_pid" 2>/dev/null; then
    echo "Chromeがデバッグ接続の準備前に終了しました。" >&2
    cat target/ui-chrome.log >&2 || true
    exit 1
  fi
  if curl --fail --silent http://127.0.0.1:9222/json/version >/dev/null 2>&1; then
    cdp_ready=true
    break
  fi
  sleep 0.25
done
if [[ "$cdp_ready" != true ]]; then
  echo "Chromeのデバッグ接続が10秒以内に準備できませんでした。" >&2
  cat target/ui-chrome.log >&2 || true
  exit 1
fi

(cd frontend && bun run verify:ui)
