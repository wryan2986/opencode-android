package dev.ryan.opencode.core.ssh

import kotlinx.serialization.Serializable

/**
 * A saved SSH destination.
 *
 * Only non-secret data lives here — the private key is in the Android keystore and
 * is never serialised. That means this profile can go in ordinary preferences
 * without leaking a credential into a backup or a log line.
 *
 * [knownHosts] holds host keys seen so far, which is the pinning state. Losing it
 * costs one TOFU re-pin, not a credential.
 */
@Serializable
data class SshProfile(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String = "ryan",
    val termType: String = "xterm-256color",
    /** Pin after first contact and refuse changes. */
    val pinHostKey: Boolean = true,
    /** Start the session attached to this tmux session, if set. */
    val tmuxSession: String = "",
    /** Run this instead of a login shell — e.g. `opencode` or `codex`. */
    val launchCommand: String = "",
    /** Known host keys, keyed by hostname. */
    val knownHosts: Map<String, String> = emptyMap(),
) {
    val address: String get() = if (port == 22) host else "$host:$port"

    /** True when the profile's key would be enough to authenticate. */
    val usable: Boolean get() = host.isNotBlank() && user.isNotBlank()
}

/**
 * The two ways this app reaches a shell, kept side by side so the terminal UI can
 * offer either.
 *
 * The distinction is the point of the SSH transport: [Opencode] dies with the
 * opencode server, [Ssh] does not. Choosing "SSH" is how you get a terminal when
 * opencode is unreachable, and how you reach a host that does not run opencode at
 * all.
 */
sealed interface TerminalRoute {
    val label: String

    data class Opencode(val host: String) : TerminalRoute {
        override val label: String get() = "opencode"
    }

    data class Ssh(val profile: SshProfile) : TerminalRoute {
        override val label: String get() = "ssh · ${profile.label}"
    }
}