#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SOURCE_SCRIPT_DIR=$SCRIPT_DIR
JAR_PATH="$SCRIPT_DIR/NyTweetDeck.jar"
DRY_RUN=0
PLATFORM_OVERRIDE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --jar) JAR_PATH=${2:-}; shift 2 ;;
    --script-dir) SOURCE_SCRIPT_DIR=${2:-}; shift 2 ;;
    --platform) PLATFORM_OVERRIDE=${2:-}; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    *) echo "未対応の引数です: $1" >&2; exit 2 ;;
  esac
done
if [ -n "$PLATFORM_OVERRIDE" ]; then
  PLATFORM_NAME=$PLATFORM_OVERRIDE
else
  case "$(uname -s)" in
    Darwin) PLATFORM_NAME=macos ;;
    Linux) PLATFORM_NAME=linux ;;
    *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
  esac
fi
case "$PLATFORM_NAME" in
  macos)
    PLATFORM=macos
    DATA_ROOT="$HOME/Library/Application Support/NyTweetDeck"
    ;;
  linux)
    PLATFORM=linux
    DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/NyTweetDeck"
    ;;
  *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
esac
APP_ROOT="$DATA_ROOT/app"
DOMAIN=ny.tweetdeck.com
INSTALLED_JAR="$APP_ROOT/NyTweetDeck.jar"
INSTALLED_LAUNCHER="$APP_ROOT/run-nytweetdeck.sh"
INSTALLED_UNINSTALLER="$APP_ROOT/uninstall-nytweetdeck.sh"
INSTALLED_AUTOSTART_UNINSTALLER="$APP_ROOT/uninstall-autostart.sh"
INSTALLED_DOMAIN_UNINSTALLER="$APP_ROOT/uninstall-local-domain.sh"
SOURCE_LAUNCHER="$SOURCE_SCRIPT_DIR/run-nytweetdeck.sh"
SOURCE_DOMAIN_INSTALLER="$SOURCE_SCRIPT_DIR/install-local-domain.sh"
SOURCE_AUTOSTART_INSTALLER="$SOURCE_SCRIPT_DIR/install-autostart.sh"
SOURCE_UNINSTALLER="$SOURCE_SCRIPT_DIR/uninstall-nytweetdeck.sh"
SOURCE_AUTOSTART_UNINSTALLER="$SOURCE_SCRIPT_DIR/uninstall-autostart.sh"
SOURCE_DOMAIN_UNINSTALLER="$SOURCE_SCRIPT_DIR/uninstall-local-domain.sh"
for required in "$JAR_PATH" "$SOURCE_LAUNCHER" "$SOURCE_DOMAIN_INSTALLER" \
    "$SOURCE_AUTOSTART_INSTALLER" "$SOURCE_UNINSTALLER" \
    "$SOURCE_AUTOSTART_UNINSTALLER" "$SOURCE_DOMAIN_UNINSTALLER"; do
  if [ ! -f "$required" ]; then
    echo "統合インストールに必要なファイルがありません: $required" >&2
    exit 1
  fi
done
if [ "$DRY_RUN" -eq 1 ]; then
  printf 'platform=%s\nappRoot=%s\ninstalledJar=%s\ninstalledLauncher=%s\ninstalledUninstaller=%s\ninstallsLocalHttps=true\nregistersAutostart=true\nrestartsAndVerifies=true\n' \
    "$PLATFORM" "$APP_ROOT" "$INSTALLED_JAR" "$INSTALLED_LAUNCHER" "$INSTALLED_UNINSTALLER"
  exit 0
fi

mkdir -p "$APP_ROOT"
chmod 700 "$APP_ROOT"
STAGING_ROOT=$(mktemp -d "$APP_ROOT/.install.XXXXXX")
SUCCESS=0
restore_file() {
  destination=$1
  backup="$destination.bak"
  if [ -f "$backup" ]; then
    mv -f "$backup" "$destination"
  elif [ -f "$destination" ]; then
    rm -f -- "$destination"
  fi
}
cleanup() {
  if [ "$SUCCESS" -ne 1 ]; then
    for destination in "$INSTALLED_JAR" "$INSTALLED_LAUNCHER" \
        "$INSTALLED_UNINSTALLER" "$INSTALLED_AUTOSTART_UNINSTALLER" \
        "$INSTALLED_DOMAIN_UNINSTALLER"; do
      restore_file "$destination"
    done
  fi
  rm -rf -- "$STAGING_ROOT"
}
trap cleanup EXIT HUP INT TERM

cp "$JAR_PATH" "$STAGING_ROOT/NyTweetDeck.jar"
cp "$SOURCE_LAUNCHER" "$STAGING_ROOT/run-nytweetdeck.sh"
cp "$SOURCE_UNINSTALLER" "$STAGING_ROOT/uninstall-nytweetdeck.sh"
cp "$SOURCE_AUTOSTART_UNINSTALLER" "$STAGING_ROOT/uninstall-autostart.sh"
cp "$SOURCE_DOMAIN_UNINSTALLER" "$STAGING_ROOT/uninstall-local-domain.sh"
for destination in "$INSTALLED_JAR" "$INSTALLED_LAUNCHER" \
    "$INSTALLED_UNINSTALLER" "$INSTALLED_AUTOSTART_UNINSTALLER" \
    "$INSTALLED_DOMAIN_UNINSTALLER"; do
  if [ -f "$destination" ]; then
    cp "$destination" "$destination.bak"
  else
    rm -f -- "$destination.bak"
  fi
done
mv -f "$STAGING_ROOT/NyTweetDeck.jar" "$INSTALLED_JAR"
mv -f "$STAGING_ROOT/run-nytweetdeck.sh" "$INSTALLED_LAUNCHER"
mv -f "$STAGING_ROOT/uninstall-nytweetdeck.sh" "$INSTALLED_UNINSTALLER"
mv -f "$STAGING_ROOT/uninstall-autostart.sh" "$INSTALLED_AUTOSTART_UNINSTALLER"
mv -f "$STAGING_ROOT/uninstall-local-domain.sh" "$INSTALLED_DOMAIN_UNINSTALLER"
chmod 600 "$INSTALLED_JAR"
chmod 700 "$INSTALLED_LAUNCHER" "$INSTALLED_UNINSTALLER" \
  "$INSTALLED_AUTOSTART_UNINSTALLER" "$INSTALLED_DOMAIN_UNINSTALLER"

HTTPS_ROOT="$DATA_ROOT/https"
HTTPS_READY=0
if [ -f "$HTTPS_ROOT/$DOMAIN.p12" ] \
    && [ -f "$HTTPS_ROOT/keystore-password" ] \
    && [ -f "$HTTPS_ROOT/nytweetdeck-local-ca.cer" ] \
    && [ -f "$HTTPS_ROOT/$DOMAIN.cer" ] \
    && grep -Eq '^[[:space:]]*127\.0\.0\.1[[:space:]]+ny\.tweetdeck\.com[[:space:]]*$' \
      /etc/hosts; then
  if [ "$PLATFORM" = linux ] && command -v openssl >/dev/null 2>&1 \
      && openssl verify -CApath /etc/ssl/certs -verify_hostname "$DOMAIN" \
        "$HTTPS_ROOT/$DOMAIN.cer" >/dev/null 2>&1; then
    HTTPS_READY=1
  elif [ "$PLATFORM" = macos ] \
      && security verify-cert -c "$HTTPS_ROOT/$DOMAIN.cer" -p ssl -s "$DOMAIN" -q; then
    HTTPS_READY=1
  fi
fi
if [ "$HTTPS_READY" -ne 1 ]; then
  NYTWEETDECK_SKIP_REGISTERED_RESTART=1 /bin/sh "$SOURCE_DOMAIN_INSTALLER"
fi
NYTWEETDECK_JAR_PATH="$INSTALLED_JAR" \
NYTWEETDECK_LAUNCHER_PATH="$INSTALLED_LAUNCHER" \
  /bin/sh "$SOURCE_AUTOSTART_INSTALLER" --start
if ! curl --fail --silent --max-time 5 \
    https://ny.tweetdeck.com/api/v1/system/status >/dev/null 2>&1; then
  echo '統合インストール後のHTTPS確認に失敗しました。' >&2
  exit 1
fi
rm -f -- "$INSTALLED_JAR.bak" "$INSTALLED_LAUNCHER.bak" \
  "$INSTALLED_UNINSTALLER.bak" "$INSTALLED_AUTOSTART_UNINSTALLER.bak" \
  "$INSTALLED_DOMAIN_UNINSTALLER.bak"
SUCCESS=1
echo 'NyTweetDeckを安定したアプリ領域へインストールし、HTTPSと自動起動を確認しました。'
echo 'アクセス先: https://ny.tweetdeck.com'
