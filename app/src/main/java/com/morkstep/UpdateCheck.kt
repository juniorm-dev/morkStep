package com.morkstep

import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Internal alpha update check.
 *
 * The newest debug build is published as `morkStep-debug-<version>.apk` into a pCloud public
 * folder; this reads that folder's listing and takes the highest version it names, so the app
 * can say an upgrade is available. **Internal alpha only** — it talks to a personal public
 * link, has no signing verification, and a failed check simply reports nothing (see README,
 * *Internal alpha update check*).
 *
 * The endpoint is Base64-encoded rather than written out, to keep the link from being a
 * greppable string in the source; that is obfuscation, not security — the link is public.
 */
object UpdateCheck {

    /** Base64 of the public-link listing URL (`https://eapi.pcloud.com/showpublink?code=…`). */
    private const val ENCODED_ENDPOINT =
        "aHR0cHM6Ly9lYXBpLnBjbG91ZC5jb20vc2hvd3B1Ymxpbms/Y29kZT1rWk00a1VaRk9uVnpDZFNoajU2ZjUxdGUwV21TSDBuRURYWA=="

    /**
     * The phone build's published name; the Wear APK (`morkStep-wear-debug-…`) is not matched.
     * The trailing `[^"]*` tolerates a suffix the folder may add — a pCloud duplicate rename
     * (`…0.16.4 (1).apk`) or an `-unsigned` release — without crossing the JSON string's quote.
     */
    private val publishedApk = Regex("""morkStep-debug-(\d+\.\d+\.\d+)[^"]*\.apk""")

    /**
     * The newest published version, or null when the folder lists none or the request fails
     * (offline, link moved, unexpected body). Blocking — call from a background dispatcher.
     */
    fun fetchLatestVersion(): String? {
        val endpoint = String(Base64.getDecoder().decode(ENCODED_ENDPOINT), Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = Constants.UPDATE_CHECK_TIMEOUT_MS
            readTimeout = Constants.UPDATE_CHECK_TIMEOUT_MS
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            latestVersionIn(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The highest `morkStep-debug-<version>.apk` version a listing body names, or null when it
     * names none. The folder is expected to hold several builds at once, so the choice is by
     * numeric version — not by listing order or file date — and a re-uploaded older build can
     * never win over a newer one.
     */
    internal fun latestVersionIn(body: String): String? =
        publishedApk.findAll(body)
            .map { it.groupValues[1] }
            .maxWithOrNull(::compareVersions)

    /** True when [remote] is a strictly newer dotted version than [current]. */
    fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

    /** Dotted-numeric comparison; a missing component counts as 0, unparsable as empty. */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.').mapNotNull { it.toIntOrNull() }
        val right = b.split('.').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }
}
