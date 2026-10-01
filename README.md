# opencode for Android

A native Android client for the opencode server that runs on your own box. It is
built around one idea: **the server holds the state, the phone is a thin view.**
That is what removes the constant reconnecting you get from a terminal app, and it
is also what makes text-to-speech practical.

Four top-level surfaces:

| | |
|---|---|
| **Chat** | Sessions, streaming replies, tool-call cards, reasoning, resync-on-reconnect |
| **Voice** | Conversational mode bound to the current session — talk, it works, it answers |
| **Shell** | A real terminal whose process lives on the server and survives any dropout |
| **Setup** | Pairing, project directory, voice and notification preferences |

---

## Why it doesn't disconnect

A terminal emulator keeps the scrollback in the phone's screen buffer, so a dropped
TCP connection means lost output and a manual reconnect. This app never does that:

- **Conversation state lives on the server.** After any gap the app just re-reads
  `GET /api/session/{id}/message` and rebuilds. There is nothing to recover and
  therefore no "reconnect your session" UX to get wrong.
- **The shell runs server-side.** The app attaches to a PTY over a WebSocket and
  tracks the byte offset it has actually rendered. On reconnect it sends that offset
  as `?cursor=N` and the server replays *only* the missed bytes. Verified
  end-to-end: dropped mid-stream, reattached from offset 4274, received the output
  produced while offline, **zero duplicated bytes**.
- **Network changes are handled explicitly.** A `ConnectivityManager.NetworkCallback`
  reattaches on WiFi ↔ cellular switches instead of waiting for a dead TCP timeout.
- **A foreground service holds the sockets** so Android cannot kill the connection
  when the app is backgrounded.

## Setup

Two things: one command on the server, the app on the phone.

### Server

```bash
curl -fsSL https://raw.githubusercontent.com/<you>/opencode-android/main/install.sh | sudo sh
```

That installs or reconfigures the headless server and prints a pairing QR. It is
idempotent — re-running rotates nothing and does not duplicate the unit — and it
preserves an existing password unless you delete `/etc/opencode/server.env`.

It picks the bind address for you: Tailscale if present, otherwise LAN, and it
warns rather than silently exposing `0.0.0.0`. It also publishes `_opencode._tcp`
over mDNS so a phone on the same Wi-Fi finds the server without typing anything.

### Phone

Install the app, then scan the QR (or type the code). Everything after that is
automatic.

## Build

```bash
./gradlew assembleDebug        # APK
./gradlew testDebugUnitTest    # 150 tests
./gradlew installDebug
```

### Release build

```bash
./scripts/make-keystore.sh     # once; writes a gitignored keystore
export OPENCODE_STORE_PASSWORD=...
export OPENCODE_KEY_PASSWORD=...
./gradlew assembleRelease
```

Passwords are read from the environment, so the secret never lands in
`keystore.properties`. Without them the release build silently falls back to debug
signing rather than failing — deliberate, so `assembleDebug` still works for
anyone who has not made a key.

## Build

```bash
export ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug        # APK
./gradlew testDebugUnitTest    # 136 tests
./gradlew installDebug
```

Toolchain: AGP 8.13, Kotlin 2.1.21, Gradle 8.14.3, compileSdk 36, minSdk 26,
Jetpack Compose (BOM 2024.12.01), OkHttp, kotlinx.serialization.

## Tests

136 unit tests, no emulator required:

- **60** terminal emulator tests — ANSI/VT parsing, scroll regions, alternate
  screen, UTF-8 split across writes, wide/combining characters, 1 MB burst
  throughput, scrollback capping.
- **4** PTY prompt-rendering tests using the real bash handshake byte stream.
- **7** terminal key-encoding tests (Enter, DEL, arrows, navigation).
- **20** speech tests — sentence streaming, markdown stripping, barge-in.
- **19** API parsing tests driven by **real captured responses**, including a
  50-session payload. These exist because the server's response envelope is
  inconsistent, and a silent mis-parse showed up as an empty session list.
- **16** tmux tests — session-name validation (including shell-injection and
  flag-injection attempts) and `list-sessions` row parsing.
- **10** discovery tests — candidate ordering, de-duplication, and port defaulting
  (notably that an `https` tunnel is never given opencode's 4096).
- **8** SSH tests — `authorized_keys` wire-format structure and host-key pinning.

`docs/PROTOCOL.md` documents the verified wire protocol, including several things
the published opencode docs get wrong.

## Layout

```
core/
  net/        Transport, OpencodeClient, EventStream (SSE), PtySocket (WS + resume)
  model/      API + event models
  store/      SettingsStore (DataStore)
  ConnectionManager.kt   connection supervision + network callbacks
  ChatRepository.kt     folds the event stream into a renderable conversation
terminal/    TerminalEmulator.kt — pure-Kotlin VT100/xterm subset, zero Android deps
voice/       VoiceEngine.kt — recognition, streamed TTS, barge-in
service/     ConnectionService (foreground), Notifications
ui/          Compose screens
```

## Verified on device

Installed on an emulator and driven against the **real** production server:

- Pairing with a real one-time code, exchanged for a session token.
- 50 real sessions listed; a real 50-message conversation rendered with tool cards
  and reasoning blocks.
- Shell attached to a live server-side PTY: `uname -n` → `Home-Server`,
  `pwd` → `/home/ryan`, with ANSI colours and a block cursor.
- Byte-offset resume proven separately against the server: dropped mid-stream,
  reattached at offset 4274, received output produced while offline, zero
  duplicated bytes.

## tmux

The terminal can attach to any tmux session running on the box, and the header
has a **tmux** button to pick one. After a reboot every session is gone, so the
base shell remains the launcher: `tmux new -A -s work` from the terminal is
still how you start one.

Nothing server-side was added or patched for this. tmux is just a program in a
PTY, so it rides the same `/api/pty` API the shell already used.

Three things about this are load-bearing:

- **The unit must not run with `PrivateTmp=true`.** tmux's default socket lives
  in `/tmp`, and a private `/tmp` gives every PTY its own tmux server — invisible
  to both your shell and the app. With it off, a session started from the phone
  shows up in your terminal and vice versa.
- **Session names are validated, not escaped.** They reach a `bash -c` string, so
  only `[A-Za-z0-9][A-Za-z0-9_-]{0,63}` is accepted. No quotes, spaces, `;`, `$`,
  backticks, and no leading `-` (which tmux would read as a flag).
- **Attach is wrapped in `exec`.** The server appends `-l` to PTY args, so
  `tmux attach -t x` would arrive as `attach -t x -l` and fail with
  `unknown flag -l`. `bash -c "exec tmux attach -t x"` both dodges that and puts
  tmux directly in charge of the PTY, so a resize reaches the client.

A size the server actually honours is required for all of this; see the PTY size
note below.

## PTY size is set after create, not during

`POST /api/pty` has no `size` field — the schema is `command, args, cwd, title,
env` with `additionalProperties: false`. The size in the create body is silently
dropped and every PTY starts at the server default **24x80**. Size only takes
effect via `PUT /api/pty/{id}`, so `ensureTerminal` now follows every create with
an explicit update. Without it the shell wraps at 80 columns while the grid is a
different width and the prompt lands in the wrong column.

## Discovery — why you pair once

Pairing is a **one-time** setup step. After it, the app re-finds the server on
every launch and never asks for a code again.

It works by keeping the session token the code produced and using it as the trust
anchor. On each connect the app races a small candidate list and asks one narrow
question of each: **does the token I already hold work here?** A host that accepts
it is the server; a host that returns 401 is a different opencode and is dropped.
That is trust-on-first-use resolved: the first successful pairing is the trust
decision, and discovery only has to relocate the thing already trusted.

Candidates, best first:

1. the host from the last successful connection
2. `home-server.tail0f4451.ts.net` — Tailscale MagicDNS
3. anything else previously seen

**This is deliberately not mDNS.** The original plan was to publish `_opencode._tcp`
and browse for it over the tailnet. That cannot work: mDNS is link-local, it
resolves on a LAN and nowhere else, and the server is on a Tailscale CGNAT address
in `100.x` that mDNS will never see. Tailscale MagicDNS does the same job for a
tailnet, resolves only within the tailnet, and survives the IP changing — which is
the case that actually needed solving.

When several candidates accept the token, the app asks rather than guessing.

## SSH terminal

The terminal can run over SSH instead of opencode, which matters because the
opencode route depends on the opencode server being up. SSH does not.

Tap the transport name in the terminal header, add a host, and copy the shown
public key into `~/.ssh/authorized_keys` on the server. From then on:

- **opencode** — PTY from `POST /api/pty`. Available only while opencode is.
- **ssh** — an independent session. Works when opencode is wedged, and works on
  hosts that do not run opencode at all.

A profile's "run on connect" is what the session starts, so `opencode`, `codex`,
`tmux attach -t work` or a plain shell are all just a value in a field.

Three bugs here were found by testing against real sshd rather than by compiling,
and all three would have shipped otherwise:

1. **The `authorized_keys` line was malformed.** An EC key blob is three SSH
   strings — type name, curve name (`nistp256`), and the uncompressed point
   (`0x04 || X || Y`). The first version omitted the curve string and the `0x04`;
   the second wrote the type name by hand, which `putPubKeyIntoBuffer` already does,
   so it appeared twice. Both produce a line that looks fine and that sshd rejects
   with a bare auth error. The encoder is now sshj's alone, and tests assert the
   field layout.
2. **BouncyCastle has to be registered as a JCE provider.** sshj resolves `"ECDSA"`
   through it explicitly; without it the first `KeyType` use throws
   `NoSuchAlgorithmException`. On Android that is a crash, not an auth failure.
3. **Resize must use `changeWindowDimensions`, never a second `allocatePTY`.** A
   PTY is allocated once per channel; a second call makes sshd drop the connection
   with `Protocol error: you already have a pty`. Since resize fires on rotation,
   this would have killed the session the first time the phone turned.

## Known gaps

Honest list of what is not finished:

- **Cloudflare Tunnel transport** is modelled in `Transport` but not wired to a real
  tunnel; Tailscale is the shipped path. SSH fallback is declared but throws
  `TransportUnavailable` rather than pretending to work. Both are config-only
  additions — the endpoint abstraction already supports a header chain.
- **Voice is untested on a real device.** The emulator has no usable microphone, so
  speech recognition and barge-in are unproven end-to-end. The sentence splitter,
  markdown stripping and barge-in detector are unit-tested, but nothing has spoken
  an actual reply yet.
- **Notifications have not been observed firing.** The channels, the permission
  notification with Approve/Deny actions, the turn-complete notification, and the
  `PermissionActionReceiver` that replays the answer without the app running are all
  implemented and wired to the event stream, but the emulator runs no LLM-backed
  session, so no real permission request was ever raised to trigger one.
- **The terminal has no scrollback view.** The grid renders the live screen only;
  `TerminalEmulator` keeps 5000 lines of scrollback and exposes it, but there is no
  UI to page through it yet.
- **No text selection or copy from the terminal**, and no clipboard bridging.
- **Tool card output is capped at 2000 characters** and the reasoning block shows
  two lines when collapsed.
- **No release signing config** beyond the optional `keystore.properties` hook; the
  shipped artifact is a debug APK.
- **Discovery covers Tailscale and LAN, not arbitrary networks.** It is a candidate
  ladder, not a protocol, so it only finds a server whose address is already
  guessable. A host behind a port-forward with no tailnet entry needs its address
  typed in once — after which it becomes a remembered candidate like any other.
- **tmux supports attach and kill, not rename.** Creating a session from the sheet
  works; renaming one still means typing `tmux rename-session` in the shell. The
  repository already has the call shape if you want it.
- **tmux sessions are only listed, never mirrored.** The sheet polls every 4s while
  open, so a session created elsewhere appears without a manual refresh, but there
  is no push notification for it.
- **An in-place resize splices the terminal grid**, because the emulator has no
  reflow. A tmux client is resized in place (a teardown would detach it), so after
  a rotation the tmux pane redraws correctly but the surrounding grid may show a
  seam until the next redraw. A plain shell is still recreated outright.
