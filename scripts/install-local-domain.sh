#!/usr/bin/env sh
set -eu

DOMAIN=ny.tweetdeck.com
DRY_RUN=0
PLATFORM_OVERRIDE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
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
    HTTPS_PORT=18443
    ;;
  linux)
    PLATFORM=linux
    DATA_ROOT="${XDG_DATA_HOME:-$HOME/.local/share}/NyTweetDeck"
    HTTPS_PORT=443
    ;;
  *) echo 'macOSまたはLinux用のスクリプトです。' >&2; exit 1 ;;
esac
HTTPS_ROOT="$DATA_ROOT/https"
KEYSTORE_PATH="$HTTPS_ROOT/$DOMAIN.p12"
PASSWORD_PATH="$HTTPS_ROOT/keystore-password"
CERTIFICATE_PATH="$HTTPS_ROOT/$DOMAIN.cer"
if [ "$DRY_RUN" -eq 1 ]; then
  printf 'platform=%s\nhost=%s\naddress=127.0.0.1\nhttpsPort=%s\nkeyStorePath=%s\n' \
    "$PLATFORM" "$DOMAIN" "$HTTPS_PORT" "$KEYSTORE_PATH"
  exit 0
fi
if ! command -v keytool >/dev/null 2>&1; then
  echo 'JDK 17、21、25に含まれるkeytoolが必要です。' >&2
  exit 1
fi
if [ "$PLATFORM" = linux ] \
    && { ! command -v update-ca-certificates >/dev/null 2>&1 \
      || ! command -v setcap >/dev/null 2>&1 \
      || ! command -v getcap >/dev/null 2>&1; }; then
  echo 'Linuxではupdate-ca-certificates、setcap、getcapが必要です。' >&2
  exit 1
fi
mkdir -p "$HTTPS_ROOT"
chmod 700 "$HTTPS_ROOT"
PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
keytool -genkeypair \
  -alias nytweetdeck \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=$DOMAIN" \
  -ext "SAN=dns:$DOMAIN,ip:127.0.0.1" \
  -ext 'EKU=serverAuth' \
  -validity 1825 \
  -noprompt
keytool -exportcert -rfc \
  -alias nytweetdeck \
  -keystore "$KEYSTORE_PATH" \
  -storepass "$PASSWORD" \
  -file "$CERTIFICATE_PATH"
printf '%s\n' "$PASSWORD" > "$PASSWORD_PATH"
chmod 600 "$KEYSTORE_PATH" "$PASSWORD_PATH" "$CERTIFICATE_PATH"

BEGIN_MARKER='# BEGIN NyTweetDeck local domain'
END_MARKER='# END NyTweetDeck local domain'
HOSTS_TEMP=$(mktemp)
awk -v begin="$BEGIN_MARKER" -v end="$END_MARKER" '
  $0 == begin { skip=1; next }
  $0 == end { skip=0; next }
  !skip { print }
' /etc/hosts > "$HOSTS_TEMP"
printf '%s\n127.0.0.1 %s\n%s\n' "$BEGIN_MARKER" "$DOMAIN" "$END_MARKER" >> "$HOSTS_TEMP"
sudo cp "$HOSTS_TEMP" /etc/hosts
rm -f -- "$HOSTS_TEMP"

if [ "$PLATFORM" = linux ]; then
  sudo cp "$CERTIFICATE_PATH" /usr/local/share/ca-certificates/nytweetdeck-local.crt
  sudo update-ca-certificates
  JAVA_BINARY=$(readlink -f "$(command -v java)")
  getcap "$JAVA_BINARY" > "$HTTPS_ROOT/java-capability-before" 2>/dev/null || true
  sudo setcap 'cap_net_bind_service=+ep' "$JAVA_BINARY"
else
  sudo security add-trusted-cert -d -r trustRoot \
    -k /Library/Keychains/System.keychain "$CERTIFICATE_PATH"
  PF_ANCHOR=/etc/pf.anchors/dev.nytweetdeck
  PF_TEMP=$(mktemp)
  printf 'rdr pass on lo0 inet proto tcp from any to 127.0.0.1 port 443 -> 127.0.0.1 port 18443\n' > "$PF_TEMP"
  sudo cp "$PF_TEMP" "$PF_ANCHOR"
  rm -f -- "$PF_TEMP"
  PF_BEGIN='# BEGIN NyTweetDeck local domain'
  PF_END='# END NyTweetDeck local domain'
  PF_CONFIG_TEMP=$(mktemp)
  awk -v begin="$PF_BEGIN" -v end="$PF_END" '
    $0 == begin { skip=1; next }
    $0 == end { skip=0; next }
    !skip { print }
  ' /etc/pf.conf > "$PF_CONFIG_TEMP"
  printf '%s\nanchor "dev.nytweetdeck"\nload anchor "dev.nytweetdeck" from "%s"\n%s\n' \
    "$PF_BEGIN" "$PF_ANCHOR" "$PF_END" >> "$PF_CONFIG_TEMP"
  sudo cp "$PF_CONFIG_TEMP" /etc/pf.conf
  rm -f -- "$PF_CONFIG_TEMP"
  sudo pfctl -f /etc/pf.conf
  sudo pfctl -E 2>/dev/null || true
fi
echo "ローカルHTTPSを設定しました: https://$DOMAIN"
