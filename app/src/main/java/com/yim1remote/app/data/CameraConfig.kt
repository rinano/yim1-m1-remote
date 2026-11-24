package com.yim1remote.app.data

/**
 * Endpoint configuration for the camera.
 *
 * The YI M1 ("小蚁微单") raises its own WiFi hotspot at 192.168.0.10 and
 * serves a plain-HTTP JSON API on it (recovered from the official app and
 * cross-checked against public reverse-engineering projects). Keeping the base URL configurable is not cosmetic: it lets the
 * app talk to a mock camera server on a developer workstation while the real
 * protocol is being recovered, and it covers users whose hotspot was configured
 * to hand out a different subnet.
 *
 * The value is persisted by [CameraConfigStore] and defaults to
 * [DEFAULT_BASE_URL]; it does **not** follow the device's current gateway, since
 * the camera must be reachable even while another transport is active.
 */
data class CameraConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
) {
    companion object {
        /** Factory default: the YI M1 camera on its own WiFi hotspot. */
        const val DEFAULT_BASE_URL: String = "http://192.168.0.10"

        private const val HTTP_PREFIX = "http://"
        private const val HTTPS_PREFIX = "https://"

        /** host[:port] with no path/query — the only authority we validate. */
        private val AUTHORITY = Regex("^[A-Za-z0-9](?:[A-Za-z0-9.\\-]*[A-Za-z0-9])?(?::\\d{1,5})?$")

        /**
         * Turns free-form user input into a canonical base URL:
         *  - trims whitespace,
         *  - prepends `http://` when the scheme is missing (`192.168.42.1:8080`
         *    is something a user would plausibly type),
         *  - drops trailing slashes so endpoint joining is predictable.
         *
         * Empty input falls back to [DEFAULT_BASE_URL]; this is intentionally
         * forgiving — use [isValidBaseUrl] to gate the save button.
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return DEFAULT_BASE_URL
            val withScheme = when {
                trimmed.startsWith(HTTP_PREFIX, ignoreCase = true) -> HTTP_PREFIX + trimmed.substring(HTTP_PREFIX.length)
                trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true) -> HTTPS_PREFIX + trimmed.substring(HTTPS_PREFIX.length)
                else -> HTTP_PREFIX + trimmed
            }
            return withScheme.trimEnd('/')
        }

        /**
         * Strict-ish check backing the settings dialog's Save button.
         *
         * Accepts `http(s)://host[:port][/optional/base/path]`. A base path is
         * allowed because that is how a mock server is mounted during testing;
         * the camera itself always answers on `/`.
         */
        fun isValidBaseUrl(raw: String): Boolean {
            val normalized = normalizeBaseUrl(raw)
            val authorityAndPath = when {
                normalized.startsWith(HTTP_PREFIX, ignoreCase = true) -> normalized.substring(HTTP_PREFIX.length)
                normalized.startsWith(HTTPS_PREFIX, ignoreCase = true) -> normalized.substring(HTTPS_PREFIX.length)
                else -> return false
            }
            if (authorityAndPath.isEmpty()) return false
            val authority = authorityAndPath.substringBefore('/')
            return AUTHORITY.matches(authority)
        }
    }
}
