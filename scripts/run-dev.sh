#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
FRONTEND_ROOT="$REPOSITORY_ROOT/frontend"
BACKEND_PORT=${NYTWEETDECK_BACKEND_PORT:-18080}
FRONTEND_PORT=${NYTWEETDECK_FRONTEND_PORT:-5173}
NO_BROWSER=${NYTWEETDECK_NO_BROWSER:-0}
EXIT_AFTER_READY=${NYTWEETDECK_EXIT_AFTER_READY:-0}
BACKEND_PID=
FRONTEND_PID=

usage() {
  echo 'Usage: ./scripts/run-dev.sh [--backend-port PORT] [--frontend-port PORT] [--no-browser] [--exit-after-ready]'
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --backend-port)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      BACKEND_PORT=$2
      shift 2
      ;;
    --frontend-port)
      [ "$#" -ge 2 ] || { usage >&2; exit 2; }
      FRONTEND_PORT=$2
      shift 2
      ;;
    --no-browser)
      NO_BROWSER=1
      shift
      ;;
    --exit-after-ready)
      EXIT_AFTER_READY=1
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "不明な引数です: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

validate_port() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
  esac
  [ "$1" -ge 1024 ] && [ "$1" -le 65535 ]
}

if ! validate_port "$BACKEND_PORT" || ! validate_port "$FRONTEND_PORT"; then
  echo 'ポートは1024〜65535の整数で指定してください。' >&2
  exit 2
fi
if [ "$BACKEND_PORT" -eq "$FRONTEND_PORT" ]; then
  echo 'バックエンドとフロントエンドには異なるポートを指定してください。' >&2
  exit 2
fi
for REQUIRED_COMMAND in java mvn bun curl; do
  if ! command -v "$REQUIRED_COMMAND" >/dev/null 2>&1; then
    echo "開発起動に必要なコマンドが見つかりません: $REQUIRED_COMMAND" >&2
    exit 1
  fi
done

JAVA_VERSION_LINE=$(java -version 2>&1 | sed -n '1p')
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -nE 's/.*version "([0-9]+).*/\1/p')
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "Java 17以上が必要です。現在のバージョン: $JAVA_VERSION_LINE" >&2
  exit 1
fi

terminate_process() (
  PROCESS_ID=$1
  [ -n "$PROCESS_ID" ] || return 0
  if command -v pgrep >/dev/null 2>&1; then
    for CHILD_ID in $(pgrep -P "$PROCESS_ID" 2>/dev/null || true); do
      terminate_process "$CHILD_ID"
    done
  fi
  kill "$PROCESS_ID" 2>/dev/null || true
)

cleanup() {
  terminate_process "$FRONTEND_PID"
  terminate_process "$BACKEND_PID"
  [ -z "$FRONTEND_PID" ] || wait "$FRONTEND_PID" 2>/dev/null || true
  [ -z "$BACKEND_PID" ] || wait "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM

wait_for_url() {
  URL=$1
  PROCESS_ID=$2
  PROCESS_NAME=$3
  ATTEMPT=0
  while [ "$ATTEMPT" -lt 120 ]; do
    if ! kill -0 "$PROCESS_ID" 2>/dev/null; then
      echo "$PROCESS_NAME が準備完了前に終了しました。" >&2
      return 1
    fi
    if curl --fail --silent --max-time 1 "$URL" >/dev/null 2>&1; then
      return 0
    fi
    ATTEMPT=$((ATTEMPT + 1))
    sleep 0.5
  done
  echo "$PROCESS_NAME が60秒以内に準備完了しませんでした: $URL" >&2
  return 1
}

echo "NyTweetDeck backendを起動します: http://127.0.0.1:$BACKEND_PORT"
(
  cd "$REPOSITORY_ROOT"
  mvn spring-boot:run -Dexec.skip=true \
    "-Dspring-boot.run.arguments=--server.port=$BACKEND_PORT"
) &
BACKEND_PID=$!
wait_for_url "http://127.0.0.1:$BACKEND_PORT/api/v1/system/status" \
  "$BACKEND_PID" 'NyTweetDeck backend'

echo "NyTweetDeck frontendを起動します: http://127.0.0.1:$FRONTEND_PORT"
(
  cd "$FRONTEND_ROOT"
  NYTWEETDECK_BACKEND_ORIGIN="http://127.0.0.1:$BACKEND_PORT" \
  NYTWEETDECK_FRONTEND_PORT="$FRONTEND_PORT" \
    bun run dev
) &
FRONTEND_PID=$!
wait_for_url "http://127.0.0.1:$FRONTEND_PORT/" "$FRONTEND_PID" 'NyTweetDeck frontend'

ACCESS_URL="http://127.0.0.1:$FRONTEND_PORT/"
echo "NyTweetDeck開発環境の準備が完了しました: $ACCESS_URL"
echo '終了するには Ctrl+C を押してください。'
if [ "$NO_BROWSER" = 1 ]; then
  :
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$ACCESS_URL" >/dev/null 2>&1 || true
elif command -v open >/dev/null 2>&1; then
  open "$ACCESS_URL"
else
  echo "ブラウザで $ACCESS_URL を開いてください。"
fi

if [ "$EXIT_AFTER_READY" = 1 ]; then
  exit 0
fi

while kill -0 "$BACKEND_PID" 2>/dev/null && kill -0 "$FRONTEND_PID" 2>/dev/null; do
  sleep 1
done
if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
  echo 'NyTweetDeck backendが終了しました。' >&2
else
  echo 'NyTweetDeck frontendが終了しました。' >&2
fi
exit 1
