#!/usr/bin/env sh
set -eu

DOMAIN=ny.tweetdeck.com
case "$(uname -s)" in
  Darwin)
    PLATFORM=macos
    DATA_ROOT="$HOME/Library/Application Support/NyTweetDeck"
    ;;
  Linux)
    PLATFORM=linux
    DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/NyTweetDeck"
    ;;
  *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
esac
HTTPS_ROOT="$DATA_ROOT/https"
CERTIFICATE_PATH="$HTTPS_ROOT/$DOMAIN.cer"
BEGIN_MARKER='# BEGIN NyTweetDeck local domain'
END_MARKER='# END NyTweetDeck local domain'
HOSTS_TEMP=$(mktemp)
awk -v begin="$BEGIN_MARKER" -v end="$END_MARKER" '
  $0 == begin { skip=1; next }
  $0 == end { skip=0; next }
  !skip { print }
' /etc/hosts > "$HOSTS_TEMP"
sudo cp "$HOSTS_TEMP" /etc/hosts
rm -f -- "$HOSTS_TEMP"

if [ "$PLATFORM" = linux ]; then
  sudo rm -f -- /usr/local/share/ca-certificates/nytweetdeck-local.crt
  sudo update-ca-certificates
  JAVA_BINARY=$(readlink -f "$(command -v java)")
  CAPABILITY_BACKUP="$HTTPS_ROOT/java-capability-before"
  if [ -s "$CAPABILITY_BACKUP" ]; then
    PREVIOUS_CAPABILITY=$(sed -n 's/^[^=]*=//p' "$CAPABILITY_BACKUP")
    if [ -n "$PREVIOUS_CAPABILITY" ]; then
      sudo setcap "$PREVIOUS_CAPABILITY" "$JAVA_BINARY"
    fi
  else
    sudo setcap -r "$JAVA_BINARY" 2>/dev/null || true
  fi
else
  if [ -f "$CERTIFICATE_PATH" ]; then
    FINGERPRINT=$(openssl x509 -in "$CERTIFICATE_PATH" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d ':')
    sudo security delete-certificate -Z "$FINGERPRINT" /Library/Keychains/System.keychain 2>/dev/null || true
  fi
  PF_BEGIN='# BEGIN NyTweetDeck local domain'
  PF_END='# END NyTweetDeck local domain'
  PF_CONFIG_TEMP=$(mktemp)
  awk -v begin="$PF_BEGIN" -v end="$PF_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
  ' /etc/pf.conf > "$PF_CONFIG_TEMP"
  sudo cp "$PF_CONFIG_TEMP" /etc/pf.conf
  rm -f -- "$PF_CONFIG_TEMP"
  sudo rm -f -- /etc/pf.anchors/dev.nytweetdeck
  sudo pfctl -f /etc/pf.conf
fi
rm -rf -- "$HTTPS_ROOT"
echo "ローカルHTTPSを解除しました: https://$DOMAIN"
