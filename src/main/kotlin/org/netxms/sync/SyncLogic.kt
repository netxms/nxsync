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
