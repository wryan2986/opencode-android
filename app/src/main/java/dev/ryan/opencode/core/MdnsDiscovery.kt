package dev.ryan.opencode.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Finds opencode servers on the local network with mDNS.
 *
 * ## Why both mDNS and MagicDNS
 *
 * An earlier version of this app carried a note that mDNS "cannot work" because
 * the development server sat on a Tailscale address. That was true of *that*
 * machine and wrong as a general claim. mDNS is link-local by design: it resolves
 * perfectly on a home LAN and is invisible across a routed tailnet. Tailscale's
 * MagicDNS is the mirror image — tailnet-only, blind to the LAN.
 *
 * A stranger setting this up will be on whichever of those they have, so the app
 * tries both. Neither is a privileged source: a candidate still has to accept the
 * stored token before it is believed (see [ServerDiscovery]).
 *
 * No permission is required — mDNS browsing is not a location or network
 * permission, and `NsdManager` works at minSdk 26.
 */
class MdnsDiscovery(private val context: Context) {

    /**
     * Resolve `_opencode._tcp` once.
     *
     * Browsing is a one-shot rather than a long-lived listener: this is called
     * when discovery runs, not held open, and a stale listener is worse than a
     * fresh one because it would keep resolving after the user has moved on.
     */
    suspend fun browse(timeoutMs: Long = 3_000): List<String> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val found = LinkedHashSet<String>()
        val done = CompletableDeferred<Unit>()
        var listener: NsdManager.DiscoveryListener? = null

        return try {
            listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(type: String?, errorCode: Int) {
                    Log.w(TAG, "mDNS discovery failed to start: $errorCode")
                    done.complete(Unit)
                }

                override fun onStopDiscoveryFailed(type: String?, errorCode: Int) = Unit

                override fun onDiscoveryStarted(type: String?) = Unit

                override fun onDiscoveryStopped(type: String?) = Unit

                override fun onServiceFound(service: NsdServiceInfo) {
                    // Anything advertising this type is a candidate, not a
                    // confirmed server — it still has to pass the token check.
                    hostOf(service)?.let { found.add(it) }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    hostOf(service)?.let { found.remove(it) }
                }
            }

            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            withTimeoutOrNull(timeoutMs) { done.await() }
            found.toList()
        } catch (e: Exception) {
            // Browsing is best-effort. A device without mDNS, or one on a network
            // that blocks it, must still reach the user by typing an address.
            Log.w(TAG, "mDNS browse failed: ${e.message}")
            emptyList()
        } finally {
            runCatching { listener?.let { nsd.stopServiceDiscovery(it) } }
        }
    }

    /**
     * The host an advertisement came from.
     *
     * `NsdServiceInfo.hostName` was removed in API 34, so this prefers the address
     * list and falls back to the hostname on older releases. Returns null when the
     * platform gave us neither, rather than guessing.
     */
    private fun hostOf(service: NsdServiceInfo): String? {
        service.hostAddresses?.firstOrNull()?.hostAddress?.takeIf { it.isNotBlank() }?.let { return it }
        // Reflection rather than a compile-time reference: the accessor was removed
        // from the SDK stubs, so naming it directly will not compile at all.
        return runCatching {
            val m = NsdServiceInfo::class.java.getMethod("hostName")
            (m.invoke(service) as? String)?.trimEnd('.')
        }.getOrNull()
    }

    companion object {
        const val TAG = "MdnsDiscovery"

        /** Registered by the install script so this finds something. */
        const val SERVICE_TYPE = "_opencode._tcp."
    }
}