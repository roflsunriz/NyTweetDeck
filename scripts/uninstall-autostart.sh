#!/usr/bin/env sh
set -eu

case "$(uname -s)" in
  Darwin)
    DESTINATION=${NYTWEETDECK_AUTOSTART_PATH:-$HOME/Library/LaunchAgents/dev.nytweetdeck.plist}
    launchctl bootout "gui/$(id -u)/dev.nytweetdeck" 2>/dev/null || true
    rm -f -- "$DESTINATION"
    ;;
  Linux)
    DESTINATION=${NYTWEETDECK_AUTOSTART_PATH:-${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/nytweetdeck.service}
    systemctl --user disable --now nytweetdeck.service 2>/dev/null || true
    rm -f -- "$DESTINATION"
    systemctl --user daemon-reload 2>/dev/null || true
    ;;
  *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
esac
echo 'NyTweetDeckのログオン自動起動を解除しました。'
