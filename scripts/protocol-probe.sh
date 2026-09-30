#!/usr/bin/env bash
# Protocol conformance probe for the opencode Android backend.
# Verifies the invariants documented in docs/PROTOCOL.md against a live server,
# so a version bump can be diffed for drift.
#
# usage: BASE=http://host:port DIR=/some/dir ./protocol-probe.sh
# Auth is read from /etc/opencode/server.env (needs sudo).
set -uo pipefail

BASE="${BASE:-http://100.102.124.47:4096}"
DIR="${DIR:-/home/ryan}"
PW="$(sudo grep PASSWORD /etc/opencode/server.env 2>/dev/null | cut -d= -f2)"
AUTH=(-u "opencode:$PW")

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

emit() { printf '%s\t%s\n' "$1" "$2"; }

# SSE probes: curl exits 28 on an open stream, so read the status line from the
# header dump instead of trusting -w. Distinguishes "401 JSON" from "200 stream".
sse_probe() { # sse_probe <label> <curl-args...>
  local label="$1"; shift
  curl -sS -g --max-time 3 -D "$TMP/h" -o "$TMP/s" "$@" 2>/dev/null
  local status ctype
  status="$(head -1 "$TMP/h" 2>/dev/null | tr -d '\r')"
  ctype="$(grep -i '^content-type' "$TMP/h" 2>/dev/null | head -1 | tr -d '\r' | cut -d' ' -f2-)"
  local first; first="$(head -c 40 "$TMP/s" 2>/dev/null | tr -d '\n')"
  emit "$label" "status=[${status:-none}] ctype=${ctype%%;*} first=[${first}]"
}

# Capture "HTTP <code> ctype=<ct>" for a curl invocation.
probe() { # probe <label> <curl-args...>
  local label="$1"; shift
  local out code ct
  # -g: literal [directory] is not a glob. --max-time: SSE probes never close.
  out="$(curl -sS -g --max-time "${TMO:-8}" -o "$TMP/body" -w '%{http_code} %{content_type}' "$@" 2>"$TMP/err")" \
    || out="CURLFAIL $(tr -d '\n' <"$TMP/err")"
  code="${out%% *}"; ct="${out#* }"
  emit "$label" "http=$code ctype=${ct%%;*}"
}

echo "### target $BASE  dir=$DIR"

# --- 1. Auth layers -------------------------------------------------------
probe "unauth.api.info"        "$BASE/api/info"
probe "unauth.api.session"     "$BASE/api/session"
probe "unauth.spa.root"        "$BASE/"
probe "unauth.openapi"         "$BASE/openapi.json"
probe "basic.api.info"         "${AUTH[@]}" "$BASE/api/info"

VER="$(curl -sS "${AUTH[@]}" "$BASE/api/info" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("version","?"))' 2>/dev/null || echo '?')"
emit "info.version" "$VER"

PATHS="$(curl -sS "${AUTH[@]}" "$BASE/openapi.json" | python3 -c 'import sys,json;print(len(json.load(sys.stdin).get("paths",{})))' 2>/dev/null || echo '?')"
emit "openapi.path_count" "$PATHS"

# --- 2. Pairing round trip ------------------------------------------------
MINT="$(curl -sS "${AUTH[@]}" -X POST "$BASE/api/pair" -o "$TMP/mint" -w '%{http_code}')"
CODE="$(python3 -c 'import json;print(json.load(open("'"$TMP"'/mint")).get("code",""))' 2>/dev/null)"
EXP="$(python3 -c 'import json;print(json.load(open("'"$TMP"'/mint")).get("expires_in",""))' 2>/dev/null)"
emit "pair.mint" "http=$MINT code_len=${#CODE} expires_in=$EXP"
emit "pair.code_charset" "$(printf '%s' "$CODE" | grep -qE '^[A-Za-z0-9_-]+$' && echo ok || echo UNEXPECTED)"

RDM="$(curl -sS "${AUTH[@]}" -o "$TMP/rdm" -w '%{http_code}' "$BASE/auth/connect/$CODE")"
TOK="$(python3 -c 'import json;print(json.load(open("'"$TMP"'/rdm")).get("token",""))' 2>/dev/null)"
emit "pair.redeem" "http=$RDM token_len=${#TOK}"

CURL_COOKIE=(-H "Cookie: opencode_session_$(echo "$BASE" | sed 's|.*:||')=$TOK")
probe "cookieonly.api.info"    "${CURL_COOKIE[@]}" "$BASE/api/info"

# Reusing a consumed code must fail.
probe "pair.reuse"             "${AUTH[@]}" "$BASE/auth/connect/$CODE"

# Wrong-case code must fail (codes are case sensitive).
UP="$(printf '%s' "$CODE" | tr 'a-z' 'A-Z')"
probe "pair.wrongcase"         "${AUTH[@]}" "$BASE/auth/connect/$UP"

# --- 3. Envelope + location scoping --------------------------------------
Q='location[directory]'
probe "session.envelope"       "${CURL_COOKIE[@]}" -G --data-urlencode "$Q=$DIR" "$BASE/api/session"
curl -sS "${CURL_COOKIE[@]}" -G --data-urlencode "$Q=$DIR" "$BASE/api/session" -o "$TMP/sess"
emit "session.shape" "$(python3 - "$TMP/sess" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception as e: print("UNDECODABLE",e); raise SystemExit
print("keys=%s n=%d" % (",".join(sorted(d.keys())), len(d.get("data",[]))))
PY
)"
# Wrong scoping form: PROTOCOL.md claims 400. Record what actually happens and
# whether the scope was honoured or silently ignored.
probe "location.badform"       "${CURL_COOKIE[@]}" "$BASE/api/session?location=$DIR"
curl -sS -g "${CURL_COOKIE[@]}" "$BASE/api/session?location=$DIR" -o "$TMP/bad" 2>/dev/null
emit "location.badform.body" "$(head -c 120 "$TMP/bad" 2>/dev/null | tr -d '\n')"

# --- 4. Prompt body shape (validate without spawning a turn) --------------
probe "prompt.parts_rejected"  "${CURL_COOKIE[@]}" -X POST -H 'content-type: application/json' \
    -G --data-urlencode "$Q=$DIR" \
    -d '{"parts":[{"type":"text","text":"x"}]}' "$BASE/api/session/nonexistent/prompt"

# --- 5. Event stream auth quirk ------------------------------------------
# PROTOCOL.md claims cookie-alone must 401. Measure each credential combo.
sse_probe "event.cookieonly"   "${CURL_COOKIE[@]}" -H 'Accept: text/event-stream' "$BASE/api/event"
sse_probe "event.basiconly"    "${AUTH[@]}"       -H 'Accept: text/event-stream' "$BASE/api/event"
sse_probe "event.both"         "${CURL_COOKIE[@]}" "${AUTH[@]}" -H 'Accept: text/event-stream' "$BASE/api/event"
sse_probe "event.none"         -H 'Accept: text/event-stream' "$BASE/api/event"

# --- 6. PTY connect-token guards ----------------------------------------
probe "pty.token.noheader"     "${CURL_COOKIE[@]}" -X POST "$BASE/api/pty/nonexistent/connect-token?$Q=$DIR"
probe "pty.token.header"       "${CURL_COOKIE[@]}" -H 'x-opencode-ticket: 1' -X POST \
    "$BASE/api/pty/nonexistent/connect-token?$Q=$DIR"
probe "pty.token.origin"       "${CURL_COOKIE[@]}" -H 'x-opencode-ticket: 1' -H 'Origin: http://evil.test' -X POST \
    "$BASE/api/pty/nonexistent/connect-token?$Q=$DIR"

# --- 7. Miscellaneous endpoint shapes ------------------------------------
probe "api.location"           "${CURL_COOKIE[@]}" "$BASE/api/location?$Q=$DIR"
probe "api.project"            "${CURL_COOKIE[@]}" "$BASE/api/project"
probe "api.info.bare"          "${CURL_COOKIE[@]}" "$BASE/api/info"
echo "### done"