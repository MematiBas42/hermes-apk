package com.hermes.client.data.remote

import okhttp3.HttpUrl

/**
 * Single decision point for "may the API key / push token be attached to this URL?".
 *
 *  - `https://` : always.
 *  - plaintext loopback (`127.0.0.1`, `localhost`, `::1`): only when the user explicitly enabled
 *    "allow credentials over local tunnel" in Settings ([allowLoopback]). This is meant for
 *    SSH / FRP tunnels whose last hop is a local port. It is opt-in because any local process
 *    that binds the port before the tunnel is up would otherwise receive the master key.
 *  - any other `http://` host: never.
 */
object CredentialPolicy {
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    fun isLoopback(url: HttpUrl): Boolean = url.host in LOOPBACK_HOSTS

    fun allows(url: HttpUrl, allowLoopback: Boolean): Boolean =
        url.isHttps || (allowLoopback && isLoopback(url))

    /**
     * True when [url] is [base] itself or lies below it, comparing scheme, host, port and whole
     * path segments (so `/hermes-files-evil` is not "under" `/hermes-files`).
     */
    fun isUnder(url: HttpUrl, base: HttpUrl): Boolean {
        if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return false
        val baseSegments = base.pathSegments.filter { it.isNotEmpty() }
        val urlSegments = url.pathSegments.filter { it.isNotEmpty() }
        if (urlSegments.size < baseSegments.size) return false
        return baseSegments.indices.all { baseSegments[it] == urlSegments[it] }
    }
}
