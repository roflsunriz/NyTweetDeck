#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR_PATH="$SCRIPT_DIR/NyTweetDeck.jar"

if [ ! -f "$JAR_PATH" ]; then
  echo "NyTweetDeck.jarが見つかりません: $JAR_PATH" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java 21以上をインストールしてから再実行してください。" >&2
  exit 1
fi

java -jar "$JAR_PATH" &
APP_PID=$!
cleanup() {
  kill "$APP_PID" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

READY=0
ATTEMPT=0
while [ "$ATTEMPT" -lt 60 ]; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then
    echo "NyTweetDeckが起動前に終了しました。" >&2
    exit 1
  fi
  if curl --fail --silent --max-time 1 http://127.0.0.1:18080/api/v1/system/status >/dev/null 2>&1; then
    READY=1
    break
  fi
  ATTEMPT=$((ATTEMPT + 1))
  sleep 0.5
done
if [ "$READY" -ne 1 ]; then
  echo "NyTweetDeckの起動が30秒以内に完了しませんでした。" >&2
  exit 1
fi

if command -v xdg-open >/dev/null 2>&1; then
  xdg-open http://127.0.0.1:18080 >/dev/null 2>&1 || true
elif command -v open >/dev/null 2>&1; then
  open http://127.0.0.1:18080
else
  echo "ブラウザで http://127.0.0.1:18080 を開いてください。"
fi

wait "$APP_PID"
