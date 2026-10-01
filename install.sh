#!/usr/bin/env bash
#
# opencode-android server install.
#
#   curl -fsSL <this-url> | sh
#
# Installs (or reconfigures) the headless opencode server that the Android client
# talks to, then prints a pairing code. The phone only ever needs that code.
#
# Design notes:
#   - idempotent. Re-running reconfigures rather than duplicating, and never
#     destroys an existing server.env without saying so.
#   - binds to the Tailscale address when there is one, because that is not
#     routable from the internet. Falls back to LAN, and refuses to guess before
#     warning about 0.0.0.0.
#   - PrivateTmp is deliberately OFF. tmux keeps its socket in /tmp, and a
#     private /tmp gives every PTY its own tmux server, which breaks the app's
#     attach-to-session feature in a way that is very hard to diagnose.
#
set -euo pipefail

UNIT=/etc/systemd/system/opencode-server.service
ENVFILE=/etc/opencode/server.env
PORT="${OPENCODE_PORT:-4096}"
SERVICE_USER="${OPENCODE_USER:-${SUDO_USER:-$USER}}"

say()  { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31mxx\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run with sudo (or as root)"

# ---- 1. locate opencode --------------------------------------------------

say "looking for the opencode binary"
# `sudo` resets $HOME to root's, so the binary installed under the *invoking*
# user's home would be invisible. Resolve that user's home explicitly.
INVOKER_HOME="$(getent passwd "$SERVICE_USER" 2>/dev/null | cut -d: -f6)"
[ -n "$INVOKER_HOME" ] || INVOKER_HOME="$HOME"
OC=""
for candidate in \
    "$INVOKER_HOME/.opencode/bin/opencode" \
    "$INVOKER_HOME/.local/bin/opencode" \
    "$(command -v opencode 2>/dev/null || true)" \
    "/usr/local/bin/opencode" \
    "/usr/bin/opencode"; do
    [ -n "$candidate" ] && [ -x "$candidate" ] && { OC="$candidate"; break; }
done

if [ -z "$OC" ]; then
    warn "opencode is not installed."
    cat >&2 <<'EOF'

    Install it first, then re-run this script:

      curl -fsSL https://opencode.ai/install | bash

EOF
    exit 1
fi
say "found $OC ($("$OC" --version 2>/dev/null | head -1))"

# ---- 2. choose a bind address -------------------------------------------

tailscale_ip() {
    command -v tailscale >/dev/null 2>&1 || return 1
    tailscale ip -4 2>/dev/null | head -1 | grep -q . || return 1
    tailscale ip -4 2>/dev/null | head -1
}

lan_ip() {
    ip -4 route get 1.1.1.1 2>/dev/null | grep -oP 'src \K[\d.]+' | head -1
}

BIND=""
METHOD=""
if TS_IP="$(tailscale_ip)"; then
    BIND="$TS_IP"; METHOD="tailscale"
elif LAN_IP="$(lan_ip)"; then
    BIND="$LAN_IP"; METHOD="lan"
else
    warn "no non-loopback address found."
    BIND="127.0.0.1"; METHOD="loopback"
fi

say "binding to $BIND ($METHOD) on port $PORT"
case "$METHOD" in
    tailscale) say "  Tailscale addresses are not routable from the internet. Good." ;;
    lan)       warn "  LAN address only — reachable from your local network. Fine for home use." ;;
    loopback)  warn "  Loopback only. The phone cannot reach this without an SSH tunnel." ;;
esac

# ---- 3. preserve or generate a password ---------------------------------

PASSWORD=""
EXISTING=0
if [ -r "$ENVFILE" ]; then
    EXISTING=1
    PASSWORD="$(grep -m1 '^OPENCODE_SERVER_PASSWORD=' "$ENVFILE" 2>/dev/null | cut -d= -f2- || true)"
fi

if [ "$EXISTING" = "1" ] && [ -n "$PASSWORD" ]; then
    say "keeping the existing server password"
else
    say "generating a new server password"
    if command -v openssl >/dev/null 2>&1; then
        PASSWORD="$(openssl rand -base64 24 | tr -d '/+=' | head -c 32)"
    else
        PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' | head -c 32)"
    fi
fi

install -d -m 0755 /etc/opencode
umask 077
cat > "$ENVFILE" <<EOF
# Written by install.sh. Root-only: it holds the server password.
# Regenerate with:  sudo rm $ENVFILE && sudo sh <this script>
OPENCODE_SERVER_USERNAME=opencode
OPENCODE_SERVER_PASSWORD=$PASSWORD
EOF
chmod 600 "$ENVFILE"
say "wrote $ENVFILE (mode 600)"

# ---- 4. systemd unit -----------------------------------------------------

say "writing $UNIT"
cat > "$UNIT" <<EOF
[Unit]
Description=opencode headless API server (Android client backend)
Documentation=https://opencode.ai/docs/server/
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$SERVICE_USER
WorkingDirectory=$INVOKER_HOME
EnvironmentFile=$ENVFILE
ExecStart=$OC serve --hostname $BIND --port $PORT
Restart=always
RestartSec=3
TimeoutStopSec=15
KillSignal=SIGINT
NoNewPrivileges=true
# PrivateTmp is intentionally OFF. tmux keeps its socket in /tmp; a private /tmp
# gives every PTY a separate tmux server, so sessions started in the app are
# invisible to your shell and vice versa.
EOF

systemctl daemon-reload
systemctl enable opencode-server.service >/dev/null 2>&1 || true
systemctl restart opencode-server.service
sleep 2

if ! systemctl is-active --quiet opencode-server.service; then
    warn "the service did not come up. Recent log:"
    journalctl -u opencode-server.service -n 20 --no-pager >&2 || true
    die "install failed"
fi
say "service is running"

# ---- 5. prove it answers before handing the user a code -----------------

INFO_URL="http://$BIND:$PORT/api/info"
READY=0
for _ in $(seq 1 15); do
    if curl -fsS -u "opencode:$PASSWORD" "$INFO_URL" >/dev/null 2>&1; then READY=1; break; fi
    sleep 1
done
[ "$READY" = "1" ] || die "server is up but did not answer on $INFO_URL"

say "server answers: $(curl -fsS -u "opencode:$PASSWORD" "$INFO_URL")"

# ---- 6. advertise over mDNS so the phone can find this on a LAN ---------

# On a home network the phone and the box share a link, and mDNS is how one
# finds the other without typing an address. avahi-daemon is usually present but
# often has no service directories on a minimal install, so create one.
MDNS_NAME="opencode"
if command -v avahi-daemon >/dev/null 2>&1; then
    say "advertising over mDNS as $MDNS_NAME.local"
    install -d -m 0755 /etc/avahi/services 2>/dev/null || true
    # umask is still 077 from the password step; avahi reads this as root only if
    # it is world-readable, and silently ignores a 600 file.
    ( umask 022; cat > /etc/avahi/services/opencode.service <<AV
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "/usr/share/avahi/service-types.dtd">
<service-group>
  <name>opencode</name>
  <service>
    <type>_opencode._tcp</type>
    <port>$PORT</port>
  </service>
</service-group>
AV
    )
    chmod 644 /etc/avahi/services/opencode.service
    systemctl reload avahi-daemon 2>/dev/null || systemctl restart avahi-daemon 2>/dev/null || true
else
    warn "avahi-daemon is not installed — the phone will need the address typed in."
    warn "  Debian/Ubuntu: sudo apt install avahi-daemon"
fi

# ---- 7. pairing code -----------------------------------------------------

CODE_JSON="$(curl -fsS -u "opencode:$PASSWORD" -X POST "http://$BIND:$PORT/api/pair" 2>/dev/null || true)"
CODE="$(printf '%s' "$CODE_JSON" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"
[ -n "$CODE" ] || die "could not mint a pairing code"

LINK="http://$BIND:$PORT/auth/connect/$CODE"

printf '\n'
printf '\033[1;32m  Server ready.\033[0m\n\n'
printf '  Address   %s\n' "$BIND:$PORT"
printf '  Code      %s   (expires in 5 minutes, one use)\n\n' "$CODE"

if command -v qrencode >/dev/null 2>&1; then
    printf '  Scan from the app, or type the code by hand:\n\n'
    qrencode -t ANSIUTF8 -m 2 "$LINK" | sed 's/^/  /'
else
    printf '  Or type this code in the app:\n\n      %s\n\n' "$CODE"
fi

cat <<EOF
  Install the app on the phone and enter the code. It will find the rest.

  The phone needs to reach $BIND:$PORT:
    - same Wi-Fi / LAN   mDNS finds it automatically
    - over Tailscale     MagicDNS finds it, using the tailnet name
    - neither            type $BIND:$PORT into Setup by hand

  Re-run this script any time to rotate the password or change the bind address.
  To remove:  sudo systemctl disable --now opencode-server.service
EOF