# opencode v2 client protocol — verified findings

All of the below was discovered empirically against a live `opencode serve` (v2.0.20) on
2026-09-30. **The published docs at opencode.ai/docs/server are stale** — they describe
v1-style unversioned paths and a `parts[]` prompt body. The real server is different.
Trust this file over the docs.

**Re-verified against 2.0.21 on 2026-09-30 with no drift.** Every claim in §1–§8 was
re-measured after upgrading the serve instance; the only differences were the version
string and per-connection event IDs. See `scripts/protocol-probe.sh` — run it after any
opencode upgrade to re-check in one shot.

## 1. Auth

Three layers, discovered by probing.

| Mechanism | How | Notes |
|---|---|---|
| HTTP basic | `OPENCODE_SERVER_PASSWORD=…` on the server; username defaults to `opencode` | Used to bootstrap pairing. |
| Pairing | `POST /api/pair` → `{code, expires_in:300}`, then `GET /auth/connect/{code}` → `{token}` | **Intended for phones/apps.** |
| Session cookie | `Cookie: opencode_session_<port>=<token>` | Grants full API access. No password on the device. |

Cookie name is `opencode_session_<port>` when the URL has an explicit port, else
`opencode_session`. Verified: cookie alone authenticates with no basic auth present.

**Onboarding flow for the app:** user scans a QR / types the 20-char code → app calls
`GET /auth/connect/{code}` → stores the token. Password never leaves the server.

### Gotchas found
- Unauthenticated requests to `/api/*` return **a real `401 application/json`** body
  (`{"_tag":"UnauthorizedError","message":"Authentication required"}`). Only *SPA*
  routes (`/`, `/config`, …) return HTML with 200 — so the old "validate content-type
  instead of the status code" workaround is not needed for API paths. (Corrected
  2026-09-30: an earlier version of this file claimed `/api/*` returned SPA HTML 200.
  Measured false on both 2.0.20 and 2.0.21.)
- The docs' `/doc` page does not embed the spec. The real spec is at **`GET /openapi.json`**
  and requires auth (116 paths — unchanged in 2.0.21).

## 2. Project scoping

Every endpoint takes an optional `location` **deepObject** query param, not a plain string:

```
/api/session?location[directory]=/home/ryan/project
```

Getting this wrong (`?location=/path`) does **not** error — it returns `200` and the
scope is silently ignored, so you get the default project's sessions and no warning.
Always use the `location[directory]=…` form and percent-encode it per HTTP client.
(Corrected 2026-09-30: an earlier version of this file claimed a
`400 InvalidRequestError: Expected object | undefined at ["location"]`. Measured false
on 2.0.20 and 2.0.21 — silently-ignored scoping is the more dangerous failure because
there is no error to notice.)

- `GET /api/project` → all projects, each `{id, canonical, time, sandboxes}`
- `GET /api/location` → `{directory, project}` for the current working dir

## 3. Response envelope

Most responses are wrapped: `{ "data": … }`. Errors are
`{"_tag":"…Error","message":"…"}`. Unwrap `.data` everywhere.

## 4. Session + message model

```
POST /api/session?location[directory]=…        → { data: Session }
POST /api/session/{id}/prompt?location[…]      → { text: "…" }        // NOT parts[]
GET  /api/session/{id}/message?location[…]      → { data: [Message], cursor: {previous, next} }
```

Message history is **paginated** with a `cursor` — supports incremental loading.

A completed turn appends a synthetic message:

```json
{ "id": "msg_…", "type": "idle", "outcome": "succeeded" }
```

`type: "idle"` is the reliable "turn finished" signal — this is what should trigger
speech output and the completion notification.

Assistant message content is an array, not a single field:

```json
"content": [
  { "type": "reasoning", "text": "…", "state": {...}, "time": {...} },
  { "type": "text", "text": "SPIKE_OK" }
]
```

## 5. Event stream (SSE)

`GET /api/event` with `Accept: text/event-stream`. **Either credential alone is
sufficient** — the session cookie returns `200 text/event-stream` on its own, and so
does basic auth. Only sending neither is a `401`.

> Corrected 2026-09-30: an earlier version of this file claimed the cookie alone 401s.
> Measured false on both 2.0.20 and 2.0.21. Sending both is still fine and costs nothing.

Wire format:

```
data: {"id":"evt_…","type":"server.connected","data":{}}

: heartbeat
```

Heartbeat comments are emitted periodically — use them as a liveness signal.

### Full event vocabulary observed in one turn

```
server.connected
session.inbox.enqueued          session.inbox.delivered
session.execution.started       session.execution.succeeded
session.instructions.updated    session.usage.updated
session.renamed
session.step.started            session.step.streamed      session.step.ended
session.reasoning.started       session.reasoning.delta     session.reasoning.ended
session.text.started            session.text.delta          session.text.ended
```

`session.text.delta` is the hook for low-latency speech: emit each completed sentence to
TTS as it arrives instead of waiting for `session.text.ended`.

`session.inbox.*` is a delivery/queue mechanism — the right primitive for queuing prompts
sent while the phone is offline.

## 6. PTY over WebSocket — the anti-disconnect primitive

This is the important one. The terminal lives **on the server** and the client is a thin
attachable view, which is what makes reconnects lossless.

### Connect sequence

```
POST /api/pty?location[directory]=…      {command,args,cwd,title,env,size}  → { data: Pty }
POST /api/pty/{ptyID}/connect-token      header: x-opencode-ticket: 1        → { data: {ticket, expires_in: 60} }
WS  /api/pty/{ptyID}/connect?ticket=…[&cursor=N][&input_protocol=1]
```

### Auth quirk (cost real time to find)

`connect-token` returns `403 Invalid PTY connect token request` unless **both**:

1. header `x-opencode-ticket: 1` (literal `1`; any other client-name value fails)
2. **no `Origin` header** — a cross-origin request is rejected with 401 (CSRF guard).
   Native clients send no Origin, so this is free for us. Browsers must be same-origin.

The PTY ticket expires after 60s — mint a fresh one on every (re)connect.

### Wire protocol

- **client → server, input:** a **raw text frame** containing literal bytes.
  JSON frames are *not* parsed and get written to the PTY verbatim.
- **server → client, data:** raw ANSI terminal bytes.
- **server → client, control:** `\0` + JSON, e.g. `\0{"cursor":4274}`.
  Distinguish with: strip leading `\0`/whitespace, then test for a leading `{`.

### Resumability — the whole point

Track the **byte offset you have actually rendered** and send it back as `?cursor=N`.
The server then replays only what you missed.

Verified end-to-end: rendered 4274 bytes → dropped socket → server kept working →
reconnected with `cursor=4274` → got the missed output, **zero duplicated bytes**
(1390 replayed vs 4274 originally), and input still worked afterwards.

Do **not** rely on the server's advertised `{"cursor":N}` frames as your resume point.
They are published on attach/detach, not continuously — a live connection sat at
`cursor: 0` for 15s while rendering 4274 bytes. Client-side tracking is the correct
source of truth.

### Screenshots without replay

`GET /api/experimental/persistent-pty/{ptyID}/snapshot` returns
`{ info, text, checkpoint (base64), cursor{x,y} }` — a **rendered screen** plus a
checkpoint. Use this for a cheap "catch me up" paint after a cold start instead of
replaying the whole scrollback. The connect endpoint declares `security: []`
(ticket-only, no basic auth).

## 7. Other endpoints worth knowing

| Endpoint | Use |
|---|---|
| `POST /api/session/{id}/interrupt` | barge-in for voice mode |
| `GET/POST /api/session/{id}/permission…/reply` | approve/deny from the phone |
| `GET /api/fs/read/*`, `/api/fs/list`, `/api/fs/find` | file browser |
| `GET /api/command` | slash-command palette |
| `GET /api/agent`, `GET /api/model/default` | agent + model pickers |
| `GET /api/session/{id}/context` | context/token usage |
| `POST /api/worktree` | worktree management |

## 8. Consequences for the app design

1. **Disconnects stop being a user-facing problem.** Server holds all state; client
   re-attaches by offset. No "reconnect" button, no lost scrollback, no lost prompts.
2. **No SSH needed for the terminal.** The PTY API replaces the embedded-SSH plan
   entirely. The SSH transport is only relevant as a *fallback* path to reach the server.
3. **Foreground service still required** — the WebSocket and mic must survive Android
   backgrounding; that is an OS policy problem, not a protocol one.
4. **Voice mode** is a client-side state machine over `session.text.delta` +
   `type:"idle"`, using `interrupt` for barge-in.

---

## Appendix: findings from building the client

Added after implementing and running the Android app against this server.

### 9. Client-side gotchas (all cost real debugging time)

**OkHttp rejects a `ws://` request URL.** `Request.Builder().url()` throws
`IllegalArgumentException: unexpected scheme: ws`. `OkHttpClient.newWebSocket()`
performs the upgrade itself, so the request URL must stay `http(s)://`. Converting
to a ws URL yourself is the bug.

**`location[directory]` must be URL-encoded per OkHttp.** Passing the literal
`location` key yields `400 InvalidRequestError: Expected object | undefined`.

**`/api/session` and `/api/session/{id}/message` return `{data, cursor}` where the
cursor values are opaque base64 strings, not nulls.** Decoding these as
`Map<String, List<Session>>` fails on the `cursor` object, and because a single
decode failure typically falls back to an empty list, the UI silently shows
"0 sessions" with no error. Model the envelope explicitly.

**Endpoint response envelopes are inconsistent.** Verified mix:
- bare: `/api/info`, `/api/project`
- enveloped: `/api/session`, `/api/session/{id}/message`, `POST /api/session`

**Pairing codes are case-sensitive** and may contain `-` and `_`. Uppercasing the
value (rather than only the display) yields `HTTP 401` on redeem.

### 10. WebSocket auth recap

OkHttp's WebSocket does **not** run client interceptors, so `AuthInterceptor` is
bypassed. The session cookie and basic-auth header must be copied onto the upgrade
request manually.

### 11. What is NOT needed

- **SSH is not required.** The PTY API replaces an embedded SSH client entirely.
  The shell already lives on the server.
- **No scrollback persistence is needed on the client.** The server's PTY replay
  buffer plus the client's tracked byte offset reconstructs the screen after any
  disconnect. This is the single biggest simplification versus a terminal emulator.

### 12. PTY size cannot be set on create

`POST /api/pty` takes `{command, args, cwd, title, env}` with
`additionalProperties: false` — **there is no `size` field**, and a `size` in the
create body is dropped without complaint. Every PTY therefore starts at the server
default 24x80. Verified 2.0.21: a create asking for 120x40 yields a PTY whose
`stty size` reports `24 80`.

Size only takes effect through `PUT /api/pty/{id}`, whose body is
`{title?, size:{cols, rows}}` ("Update the title or viewport size of one PTY
session"). Verified: create → `PUT {size:{cols:200,rows:60}}` → `stty size` reports
`60 200`. So the client must PUT immediately after every create, and again whenever
the viewport changes.

Also note the server **appends `-l` to `args`**. An `args` of
`["attach","-t","work"]` arrives as `attach -t work -l`, which tmux rejects with
`unknown flag -l`. Wrap such commands as `["-c", "exec tmux attach -t work"]`.

### 13. `PrivateTmp` isolates tmux

The systemd unit for the serve instance must **not** set `PrivateTmp=true`. tmux's
default socket is `/tmp/tmux-$UID/default`, and a private `/tmp` gives the service
(and every PTY it spawns) its own isolated tmux server. Verified: with
`PrivateTmp=true`, a PTY running `TMUX_TMPDIR=/tmp tmux ls` could not see a
session created in the host shell; with it disabled, plain `tmux ls` from a PTY
lists the host's sessions and a session created from a PTY appears in the host
shell.

`TMUX_TMPDIR` is not a reliable workaround: when `$TMUX` is set (i.e. the caller is
itself inside a tmux client) tmux ignores it entirely and connects to `$TMUX`'s
socket. Use the default socket and keep `PrivateTmp` off.

### 14. Client bugs found while building this app

Recorded because each one produced a *plausible-looking* wrong UI rather than an error,
which is what made them expensive.

**OkHttp rejects `ws://`.** `Request.Builder().url()` throws
`IllegalArgumentException: unexpected scheme: ws`. `newWebSocket()` does the upgrade
itself, so the request URL must stay `http(s)://`.

**A `SharedFlow` with `replay = 0` silently loses the shell prompt.** The PTY emits its
opening handshake as soon as it connects — typically *before* the UI has subscribed.
With no replay, those bytes are gone, and because bash then blocks waiting for input,
no further output ever arrives to trigger a redraw. The screen stays blank while the
byte-offset counter climbs past the truth. Fix: keep a bounded output history in
`PtySocket` and let a new consumer backfill with `outputSince(offset)`.

**Two PTYs get created for one terminal.** Guarding with `_ptyId.value != null` is racy:
a tab switch plus a recomposition both see `null` before either assigns. Two shells then
feed one emulator, and the prompt appears to be drawn twice. Fix: `AtomicBoolean`
single-flight guard.

**Cell colours are not ARGB.** The emulator encodes a colour three ways — a negative
`DEFAULT_FG`/`DEFAULT_BG` sentinel, a *tagged* 256-palette index
(`PALETTE_INDEX_FLAG`), or packed RGB. Passing the raw int to Compose's `Color()`
renders the tagged form as near-transparent, so a coloured bash prompt came out almost
invisible. Always resolve via `isPaletteIndex()` / `Xterm256.rgb()` first.

**The grid and the PTY must agree on width.** Creating the PTY at 100 columns and then
resizing the grid to the ~55 that fit on screen makes the shell wrap for a width the
renderer does not have, so the prompt lands in the wrong place. Create the PTY at the
measured viewport size.

**Printable keys never arrive as key events.** The soft keyboard delivers them as IME
text commits, which bypass `onKeyEvent` entirely; without a hidden text field the
keyboard opens and nothing is typed. And the IME reports the *whole buffer* on every
commit, so forwarding it verbatim echoes earlier characters — send only the delta.

**Event payloads are not uniform.** Tool `id`/`name`/`executed` sit at the top level of
a message content part, while `input`/`output`/`status` live in its `state` object.
Reading the name out of `state` yields a generic `tool` label for every historical call.

### 15. A session token is a durable, portable trust anchor

The token from `GET /auth/connect/{code}` keeps working after the server restarts
(verified: token minted before a `systemctl restart` still returns `200` on
`/api/info`), and it is scoped to the instance that minted it. Verified matrix:

| Target | Token accepted |
|---|---|
| `100.102.124.47:4096` (our serve instance) | `200` |
| `home-server.tail0f4451.ts.net:4096` (same server, MagicDNS) | `200`, same pid |
| `127.0.0.1:49374` (a different opencode) | `401` |
| nothing listening | no answer |

That makes the token usable as a discovery credential: probe a candidate host with
the token and a `200` identifies the server without ever asking what "opencode"
looks like. It also means `/api/info` staying `401` when unauthenticated is not a
blocker for discovery — the probe is authenticated by design.

### 16. Pairing codes are per-instance (a real trap)

There are **two separate opencode server processes** on this box:

| Process | Port | Who talks to it |
|---|---|---|
| background service (`opencode service`) | `127.0.0.1:49374` | bare `opencode pair`, `opencode` TUI |
| `opencode serve` (systemd `opencode-server.service`) | `100.102.124.47:4096` | the Android app |

**A pairing code minted by one is rejected by the other** with
`401 {"_tag":"UnauthorizedError","message":"Pairing link expired or already used"}`.
Codes are held in per-instance state, so the message is actively misleading — it
reads as "expired" when the real cause is "wrong instance".

`opencode pair --url http://…:4096` only rewrites the *printed link*; the code is
still minted against the background service, so it does not help.

**Mint app codes against 4096 explicitly:**

```bash
curl -u opencode:$(sudo grep PASSWORD /etc/opencode/server.env | cut -d= -f2) \
     -X POST http://100.102.124.47:4096/api/pair
```

Verified: a code from 49374 returns 200 there and 401 on 4096; a code minted on 4096
redeems to a token that authenticates against `/api/info`.
