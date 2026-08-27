#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
case "$(uname -s)" in
  Darwin) DATA_ROOT="$HOME/Library/Application Support/NyTweetDeck" ;;
  Linux) DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/NyTweetDeck" ;;
  *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
esac
APP_ROOT="$DATA_ROOT/app"
/bin/sh "$SCRIPT_DIR/uninstall-autostart.sh"
/bin/sh "$SCRIPT_DIR/uninstall-local-domain.sh"
if [ -d "$APP_ROOT" ]; then
  case "$APP_ROOT" in
    "$DATA_ROOT"/app) rm -rf -- "$APP_ROOT" ;;
    *) echo "想定外のアプリ領域です: $APP_ROOT" >&2; exit 1 ;;
  esac
fi
echo 'NyTweetDeck本体、自動起動、ローカルHTTPS設定を解除しました。'
