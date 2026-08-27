#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR_PATH="${NYTWEETDECK_JAR_PATH:-$SCRIPT_DIR/NyTweetDeck.jar}"

if [ ! -f "$JAR_PATH" ]; then
  echo "NyTweetDeck.jarが見つかりません: $JAR_PATH" >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo "Java 17、21、25のいずれかをインストールしてから再実行してください。" >&2
  exit 1
fi
JAVA_VERSION_LINE=$(java -version 2>&1 | sed -n '1p')
JAVA_MAJOR=$(printf '%s\n' "$JAVA_VERSION_LINE" | sed -nE 's/.*version "([0-9]+).*/\1/p')
if [ -z "$JAVA_MAJOR" ]; then
  echo "Javaのバージョンを確認できませんでした: $JAVA_VERSION_LINE" >&2
  exit 1
fi
if [ "$JAVA_MAJOR" -lt 17 ]; then
  echo "Java 17以上が必要です。現在のメジャーバージョン: $JAVA_MAJOR" >&2
  exit 1
fi

case "$(uname -s)" in
  Darwin)
    DATA_ROOT="$HOME/Library/Application Support/NyTweetDeck"
    HTTPS_PORT=18443
    ;;
  *)
    DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/NyTweetDeck"
    HTTPS_PORT=443
    ;;
esac
KEYSTORE_PATH="$DATA_ROOT/https/ny.tweetdeck.com.p12"
PASSWORD_PATH="$DATA_ROOT/https/keystore-password"
ROOT_CERTIFICATE_PATH="$DATA_ROOT/https/nytweetdeck-local-ca.cer"
set -- -jar "$JAR_PATH"
ACCESS_URL=http://127.0.0.1:18080
if [ -f "$KEYSTORE_PATH" ] && [ -f "$PASSWORD_PATH" ] \
    && [ -f "$ROOT_CERTIFICATE_PATH" ]; then
  KEYSTORE_PASSWORD=$(sed -n '1p' "$PASSWORD_PATH")
  set -- "$@" \
    "--server.port=$HTTPS_PORT" \
    '--server.ssl.enabled=true' \
    "--server.ssl.key-store=$KEYSTORE_PATH" \
    "--server.ssl.key-store-password=$KEYSTORE_PASSWORD" \
    '--server.ssl.key-store-type=PKCS12' \
    '--nytweetdeck.http.port=18080'
  ACCESS_URL=https://ny.tweetdeck.com
elif [ -f "$KEYSTORE_PATH" ] || [ -f "$PASSWORD_PATH" ]; then
  echo 'ローカルHTTPS証明書が旧形式です。警告の出ない専用CA形式へ更新するには、install-local-domain.shを再実行してください。今回はHTTPで起動します。' >&2
fi

java "$@" &
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

if [ "${NYTWEETDECK_NO_BROWSER:-0}" = "1" ]; then
  :
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "$ACCESS_URL" >/dev/null 2>&1 || true
elif command -v open >/dev/null 2>&1; then
  open "$ACCESS_URL"
else
  echo "ブラウザで http://127.0.0.1:18080 を開いてください。"
fi

if [ "${NYTWEETDECK_EXIT_AFTER_READY:-0}" = "1" ]; then
  exit 0
fi

wait "$APP_PID"
