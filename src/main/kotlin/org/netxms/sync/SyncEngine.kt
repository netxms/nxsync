package org.netxms.sync

import org.netxms.client.NXCSession
import org.netxms.client.objects.AbstractObject
import org.netxms.client.server.AgentFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

/** Recursion cap for [SyncEngine.scan]; guards against symlink loops in the agent tree. */
private const val MAX_SCAN_DEPTH = 512

/**
 * Session-facing sync plumbing: scans a node's agent files under [source] and
 * mirrors changed files into `<destination>/<objectId>-<name>/`, delegating all
 * decisions to the pure functions in SyncLogic. Local files are never deleted.
 */
class SyncEngine(
    private val session: NXCSession,
    private val source: String,
    private val destination: Path,
    private val dryRun: Boolean = false,
    private val verbose: Boolean = false,
) {
    /** Sync a single node, returning its per-node report. Node-level failures isolate this node. */
    fun sync(node: AbstractObject): NodeReport {
        val report = NodeReport(node.objectName)
        val nodeDir = destination.resolve("${node.objectId}-${sanitizeNodeName(node.objectName)}")

        val roots = try {
            session.listAgentFiles(null, "/", node.objectId)
        } catch (e: Exception) {
            report.error("failed to list agent file roots: ${e.message}")
            return report
        }
        if (roots.none { isUnderRoot(source, it.fullName) }) {
            report.error("\"$source\" is not under any FILEMGR root; check the FILEMGR section in the agent configuration")
            return report
        }

        val files = mutableListOf<AgentFile>()
        scan(AgentFile(source, AgentFile.DIRECTORY, null, node.objectId), node.objectId, report, files, 0)

        for (file in files) {
            syncFile(node.objectId, report, nodeDir, file)
        }
        return report
    }

    private fun scan(dir: AgentFile, nodeId: Long, report: NodeReport, out: MutableList<AgentFile>, depth: Int) {
        if (depth > MAX_SCAN_DEPTH) {
            report.error("directory tree exceeds max depth ($MAX_SCAN_DEPTH) at ${dir.filePath}; possible symlink loop")
            return
        }
        val entries = try {
            session.listAgentFiles(dir, dir.filePath, nodeId)
        } catch (e: Exception) {
            report.error("failed to list ${dir.filePath}: ${e.message}")
            return
        }
        for (entry in entries) {
            if (entry.isDirectory) {
                scan(entry, nodeId, report, out, depth + 1)
            } else {
                out.add(entry)
            }
        }
    }

    private fun syncFile(nodeId: Long, report: NodeReport, nodeDir: Path, file: AgentFile) {
        val relative = relativePath(file.filePath, source)
        val target = resolveTarget(nodeDir, relative)
        if (target == null) {
            report.error("refusing to write outside destination: ${file.filePath}")
            System.err.println(prefix(report) + "path escape rejected: ${file.filePath}")
            return
        }

        val action = try {
            val remoteSize = file.size
            val remoteMtimeMs = file.modificationTime?.time ?: 0L
            val localExists = Files.exists(target)
            val localSize = if (localExists) Files.size(target) else 0L
            val localMtimeMs = if (localExists) Files.getLastModifiedTime(target).toMillis() else 0L

            val decided = decide(localExists, localSize, localMtimeMs, remoteSize, remoteMtimeMs)
            val verb = if (decided == Action.DOWNLOAD) "download" else "update"
            when {
                decided == Action.SKIP -> if (verbose) println(prefix(report) + "skip $relative")
                dryRun -> println(prefix(report) + "would $verb $relative")
                else -> {
                    download(nodeId, file.fullName, target, remoteMtimeMs)
                    println(prefix(report) + "$verb $relative")
                }
            }
            decided
        } catch (e: Exception) {
            report.error("failed to sync $relative: ${e.message}")
            System.err.println(prefix(report) + "sync failed: $relative: ${e.message}")
            return
        }
        report.record(action)
    }

    private fun download(nodeId: Long, remoteName: String, target: Path, remoteMtimeMs: Long) {
        val data = session.downloadFileFromAgent(nodeId, remoteName, 0, false, null)
            ?: throw IOException("agent returned no file data for $remoteName")
        stageAndCommit(data.file.toPath(), target, remoteMtimeMs)
    }

    private fun prefix(report: NodeReport) = "[${report.nodeName}] "
}

/**
 * Place [temp] at [target] via a unique staging file in the target directory (so
 * the final rename is same-filesystem and atomic) and stamp its mtime to
 * [remoteMtimeMs]. A random staging name avoids clobbering any local file, and it
 * is always cleaned up, even if the move fails.
 */
internal fun stageAndCommit(temp: Path, target: Path, remoteMtimeMs: Long) {
    val dir = target.parent ?: target.toAbsolutePath().parent
    Files.createDirectories(dir)
    val staging = Files.createTempFile(dir, ".nxsync-", ".tmp")
    try {
        try {
            Files.move(temp, staging, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.copy(temp, staging, StandardCopyOption.REPLACE_EXISTING)
            Files.deleteIfExists(temp)
        }
        try {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(remoteMtimeMs))
    } finally {
        Files.deleteIfExists(staging)
    }
}
