package org.netxms.sync

import java.nio.file.Path

enum class Action { DOWNLOAD, UPDATE, SKIP }

/**
 * Change detection: local missing -> DOWNLOAD; size differs -> UPDATE;
 * mtime differs (compared at second granularity) -> UPDATE; else SKIP.
 */
fun decide(
    localExists: Boolean,
    localSize: Long,
    localMtimeMs: Long,
    remoteSize: Long,
    remoteMtimeMs: Long,
): Action {
    if (!localExists) return Action.DOWNLOAD
    if (localSize != remoteSize) return Action.UPDATE
    if (localMtimeMs / 1000 != remoteMtimeMs / 1000) return Action.UPDATE
    return Action.SKIP
}

/** True if [path] equals [root] or is nested under it, comparing on path-separator boundaries. */
fun isUnderRoot(path: String, root: String): Boolean {
    val p = path.replace('\\', '/').trimEnd('/')
    val r = root.replace('\\', '/').trimEnd('/')
    return p == r || p.startsWith("$r/")
}

/** Node directory name: keep [a-zA-Z0-9._-], replace the rest with '_', trim '_'; blank -> "node". */
fun sanitizeNodeName(name: String): String {
    val sanitized = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('_')
    return sanitized.ifBlank { "node" }
}

/** Path of a remote file relative to SOURCE: strip the source prefix, normalize '\' to '/', drop leading separators. */
fun relativePath(filePath: String, source: String): String {
    return filePath.removePrefix(source)
        .replace('\\', '/')
        .trimStart('/')
}

/**
 * Resolve [relative] against [nodeDir], guarding against path traversal.
 * Returns the normalized target path, or null if it escapes [nodeDir].
 */
fun resolveTarget(nodeDir: Path, relative: String): Path? {
    val base = nodeDir.normalize()
    val target = base.resolve(relative).normalize()
    return if (target.startsWith(base)) target else null
}

const val DEFAULT_SERVER_PORT = 4701

data class ServerAddress(val host: String, val port: Int)

/**
 * Parse `host`, `host:port`, `[ipv6]` or `[ipv6]:port`. An unbracketed address with
 * more than one ':' must be a bare IPv6 literal. Returns null on malformed input.
 */
fun parseServerAddress(s: String): ServerAddress? {
    val host: String
    val portText: String?
    if (s.startsWith("[")) {
        val close = s.indexOf(']')
        if (close < 0) return null
        host = s.substring(1, close)
        val rest = s.substring(close + 1)
        portText = when {
            rest.isEmpty() -> null
            rest.startsWith(":") -> rest.substring(1)
            else -> return null
        }
    } else when (s.count { it == ':' }) {
        0 -> {
            host = s
            portText = null
        }
        1 -> {
            host = s.substringBefore(':')
            portText = s.substringAfter(':')
        }
        else -> {
            if (!looksLikeIpv6(s)) return null
            host = s
            portText = null
        }
    }
    if (host.isEmpty()) return null
    if (portText == null) return ServerAddress(host, DEFAULT_SERVER_PORT)
    val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return ServerAddress(host, port)
}

private val IPV6_CHARS = Regex("[0-9A-Fa-f:.]+(%.+)?")

/** Cheap shape check that tells a bare IPv6 literal from a mistyped `host:port`; not a full validator. */
private fun looksLikeIpv6(s: String): Boolean {
    if (!IPV6_CHARS.matches(s)) return false
    val addr = s.substringBefore('%')
    if (":::" in addr || addr.split("::").size > 2) return false
    if (addr.startsWith(":") && !addr.startsWith("::")) return false
    if (addr.endsWith(":") && !addr.endsWith("::")) return false
    return true
}
