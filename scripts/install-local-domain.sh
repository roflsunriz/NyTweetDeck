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
ROOT_CERTIFICATE_PATH="$HTTPS_ROOT/nytweetdeck-local-ca.cer"
if [ "$DRY_RUN" -eq 1 ]; then
  printf 'platform=%s\nhost=%s\naddress=127.0.0.1\nhttpsPort=%s\nkeyStorePath=%s\nrootCertificatePath=%s\n' \
    "$PLATFORM" "$DOMAIN" "$HTTPS_PORT" "$KEYSTORE_PATH" "$ROOT_CERTIFICATE_PATH"
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
WORK_ROOT=$(mktemp -d "$HTTPS_ROOT/.install.XXXXXX")
cleanup() {
  rm -rf -- "$WORK_ROOT"
}
trap cleanup EXIT HUP INT TERM

PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
CA_PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
CA_KEYSTORE="$WORK_ROOT/nytweetdeck-local-ca.p12"
STAGED_KEYSTORE="$WORK_ROOT/$DOMAIN.p12"
STAGED_PASSWORD="$WORK_ROOT/keystore-password"
STAGED_CERTIFICATE="$WORK_ROOT/$DOMAIN.cer"
STAGED_ROOT_CERTIFICATE="$WORK_ROOT/nytweetdeck-local-ca.cer"
CERTIFICATE_REQUEST="$WORK_ROOT/$DOMAIN.csr"

keytool -genkeypair \
  -alias nytweetdeck-local-ca \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "$CA_KEYSTORE" \
  -storepass "$CA_PASSWORD" \
  -keypass "$CA_PASSWORD" \
  -dname 'CN=NyTweetDeck Local Root CA' \
  -ext 'BC=ca:true,pathlen:0' \
  -ext 'KU=keyCertSign,cRLSign' \
  -validity 3650 \
  -noprompt
keytool -exportcert -rfc \
  -alias nytweetdeck-local-ca \
  -keystore "$CA_KEYSTORE" \
  -storepass "$CA_PASSWORD" \
  -file "$STAGED_ROOT_CERTIFICATE"

keytool -genkeypair \
  -alias nytweetdeck \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore "$STAGED_KEYSTORE" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -dname "CN=$DOMAIN" \
  -ext "SAN=dns:$DOMAIN,ip:127.0.0.1" \
  -ext 'EKU=serverAuth' \
  -ext 'BC=ca:false' \
  -validity 825 \
  -noprompt
keytool -certreq \
  -alias nytweetdeck \
  -keystore "$STAGED_KEYSTORE" \
  -storepass "$PASSWORD" \
  -file "$CERTIFICATE_REQUEST" \
  -ext "SAN=dns:$DOMAIN,ip:127.0.0.1"
keytool -gencert -rfc \
  -alias nytweetdeck-local-ca \
  -keystore "$CA_KEYSTORE" \
  -storepass "$CA_PASSWORD" \
  -infile "$CERTIFICATE_REQUEST" \
  -outfile "$STAGED_CERTIFICATE" \
  -ext "SAN=dns:$DOMAIN,ip:127.0.0.1" \
  -ext 'EKU=serverAuth' \
  -ext 'KU=digitalSignature,keyEncipherment' \
  -ext 'BC=ca:false' \
  -validity 825
keytool -importcert \
  -alias nytweetdeck-local-ca \
  -keystore "$STAGED_KEYSTORE" \
  -storepass "$PASSWORD" \
  -file "$STAGED_ROOT_CERTIFICATE" \
  -noprompt
keytool -importcert \
  -alias nytweetdeck \
  -keystore "$STAGED_KEYSTORE" \
  -storepass "$PASSWORD" \
  -file "$STAGED_CERTIFICATE" \
  -noprompt
printf '%s\n' "$PASSWORD" > "$STAGED_PASSWORD"
chmod 600 "$STAGED_KEYSTORE" "$STAGED_PASSWORD" "$STAGED_CERTIFICATE" "$STAGED_ROOT_CERTIFICATE"

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
  sudo rm -f -- /usr/local/share/ca-certificates/nytweetdeck-local.crt
  sudo cp "$STAGED_ROOT_CERTIFICATE" \
    /usr/local/share/ca-certificates/nytweetdeck-local-ca.crt
  sudo update-ca-certificates
  JAVA_BINARY=$(readlink -f "$(command -v java)")
  getcap "$JAVA_BINARY" > "$HTTPS_ROOT/java-capability-before" 2>/dev/null || true
  sudo setcap 'cap_net_bind_service=+ep' "$JAVA_BINARY"
else
  if [ -f "$ROOT_CERTIFICATE_PATH" ]; then
    OLD_FINGERPRINT=$(openssl x509 -in "$ROOT_CERTIFICATE_PATH" -noout -fingerprint -sha1 \
      | cut -d= -f2 | tr -d ':')
    sudo security delete-certificate -Z "$OLD_FINGERPRINT" \
      /Library/Keychains/System.keychain 2>/dev/null || true
  elif [ -f "$CERTIFICATE_PATH" ]; then
    OLD_FINGERPRINT=$(openssl x509 -in "$CERTIFICATE_PATH" -noout -fingerprint -sha1 \
      | cut -d= -f2 | tr -d ':')
    sudo security delete-certificate -Z "$OLD_FINGERPRINT" \
      /Library/Keychains/System.keychain 2>/dev/null || true
  fi
  sudo security add-trusted-cert -d -r trustRoot \
    -k /Library/Keychains/System.keychain "$STAGED_ROOT_CERTIFICATE"
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

mv -f "$STAGED_KEYSTORE" "$KEYSTORE_PATH"
mv -f "$STAGED_PASSWORD" "$PASSWORD_PATH"
mv -f "$STAGED_CERTIFICATE" "$CERTIFICATE_PATH"
mv -f "$STAGED_ROOT_CERTIFICATE" "$ROOT_CERTIFICATE_PATH"
RUNTIME_VERIFIED=0
if [ "${NYTWEETDECK_SKIP_REGISTERED_RESTART:-0}" != 1 ]; then
  REGISTERED=0
  if [ "$PLATFORM" = linux ] && command -v systemctl >/dev/null 2>&1 \
      && systemctl --user is-enabled nytweetdeck.service >/dev/null 2>&1; then
    systemctl --user restart nytweetdeck.service
    REGISTERED=1
  elif [ "$PLATFORM" = macos ] \
      && launchctl print "gui/$(id -u)/dev.nytweetdeck" >/dev/null 2>&1; then
    launchctl kickstart -k "gui/$(id -u)/dev.nytweetdeck"
    REGISTERED=1
  fi
  if [ "$REGISTERED" -eq 1 ]; then
    READY=0
    ATTEMPT=0
    while [ "$ATTEMPT" -lt 120 ]; do
      if curl --fail --silent --max-time 2 \
          "https://$DOMAIN/api/v1/system/status" >/dev/null 2>&1; then
        READY=1
        break
      fi
      ATTEMPT=$((ATTEMPT + 1))
      sleep 0.5
    done
    if [ "$READY" -ne 1 ]; then
      echo '登録済みNyTweetDeckの再起動後HTTPS確認に失敗しました。' >&2
      exit 1
    fi
    RUNTIME_VERIFIED=1
  fi
fi
if [ "$RUNTIME_VERIFIED" -eq 1 ]; then
  echo "ローカルHTTPSを設定し、再起動後の応答を確認しました: https://$DOMAIN"
else
  echo "ローカルHTTPSを設定しました: https://$DOMAIN"
  echo '次回のNyTweetDeck起動時からHTTPSが有効になります。'
fi
