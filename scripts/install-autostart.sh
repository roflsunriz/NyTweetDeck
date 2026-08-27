#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PLATFORM=""
DRY_RUN=0
START_NOW=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --platform) PLATFORM=${2:-}; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --start) START_NOW=1; shift ;;
    *) echo "未対応の引数です: $1" >&2; exit 2 ;;
  esac
done
if [ -z "$PLATFORM" ]; then
  case "$(uname -s)" in
    Darwin) PLATFORM=macos ;;
    Linux) PLATFORM=linux ;;
    *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
  esac
fi
JAR_PATH=${NYTWEETDECK_JAR_PATH:-$SCRIPT_DIR/NyTweetDeck.jar}
LAUNCHER_PATH="$SCRIPT_DIR/run-nytweetdeck.sh"
if [ ! -f "$JAR_PATH" ]; then
  echo "NyTweetDeck.jarが見つかりません: $JAR_PATH" >&2
  exit 1
fi
if [ ! -f "$LAUNCHER_PATH" ]; then
  echo "ランチャーが見つかりません: $LAUNCHER_PATH" >&2
  exit 1
fi

xml_escape() {
  printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/>/\&gt;/g; s/"/\&quot;/g'
}
systemd_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g; s/%/%%/g'
}

if [ "$PLATFORM" = macos ]; then
  DESTINATION=${NYTWEETDECK_AUTOSTART_PATH:-$HOME/Library/LaunchAgents/dev.nytweetdeck.plist}
  ESCAPED_LAUNCHER=$(xml_escape "$LAUNCHER_PATH")
  ESCAPED_JAR=$(xml_escape "$JAR_PATH")
  CONTENT=$(printf '%s\n' \
    '<?xml version="1.0" encoding="UTF-8"?>' \
    '<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">' \
    '<plist version="1.0"><dict>' \
    '<key>Label</key><string>dev.nytweetdeck</string>' \
    '<key>ProgramArguments</key><array>' \
    '<string>/bin/sh</string>' \
    "<string>$ESCAPED_LAUNCHER</string>" \
    '</array>' \
    '<key>EnvironmentVariables</key><dict>' \
    '<key>NYTWEETDECK_NO_BROWSER</key><string>1</string>' \
    "<key>NYTWEETDECK_JAR_PATH</key><string>$ESCAPED_JAR</string>" \
    '</dict>' \
    '<key>RunAtLoad</key><true/>' \
    '<key>KeepAlive</key><true/>' \
    '</dict></plist>')
elif [ "$PLATFORM" = linux ]; then
  DESTINATION=${NYTWEETDECK_AUTOSTART_PATH:-${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/nytweetdeck.service}
  ESCAPED_LAUNCHER=$(systemd_escape "$LAUNCHER_PATH")
  ESCAPED_JAR=$(systemd_escape "$JAR_PATH")
  CONTENT=$(printf '%s\n' \
    '[Unit]' \
    'Description=NyTweetDeck local application' \
    'After=network-online.target' \
    '' \
    '[Service]' \
    'Type=simple' \
    'Environment="NYTWEETDECK_NO_BROWSER=1"' \
    "Environment=\"NYTWEETDECK_JAR_PATH=$ESCAPED_JAR\"" \
    "ExecStart=/bin/sh \"$ESCAPED_LAUNCHER\"" \
    'Restart=on-failure' \
    'RestartSec=5' \
    '' \
    '[Install]' \
    'WantedBy=default.target')
else
  echo "未対応のプラットフォームです: $PLATFORM" >&2
  exit 2
fi
if [ "$DRY_RUN" -eq 1 ]; then
  printf 'destination=%s\n%s\n' "$DESTINATION" "$CONTENT"
  exit 0
fi
mkdir -p "$(dirname -- "$DESTINATION")"
printf '%s\n' "$CONTENT" > "$DESTINATION"
if [ "$PLATFORM" = macos ]; then
  launchctl bootout "gui/$(id -u)/dev.nytweetdeck" 2>/dev/null || true
  launchctl bootstrap "gui/$(id -u)" "$DESTINATION"
elif command -v systemctl >/dev/null 2>&1; then
  systemctl --user daemon-reload
  systemctl --user enable nytweetdeck.service
  if [ "$START_NOW" -eq 1 ]; then systemctl --user start nytweetdeck.service; fi
else
  echo 'systemdユーザーサービスを利用できません。生成済みunitを手動で有効化してください。' >&2
  exit 1
fi
echo "NyTweetDeckのログオン自動起動を登録しました: $DESTINATION"
