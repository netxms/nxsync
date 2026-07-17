package org.netxms.sync

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintCompletionMessage
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.netxms.client.NXCSession
import org.netxms.client.objects.AbstractObject
import org.netxms.client.objects.Node
import java.nio.file.Paths
import kotlin.system.exitProcess

/**
 * Password precedence: explicit `-p` wins, then `NXSYNC_PASSWORD`, then an
 * interactive prompt (only if `--ask-password`); otherwise an empty password.
 */
fun resolvePassword(option: String?, envValue: String?, askFlag: Boolean, promptFn: () -> String?): String {
    if (option != null) return option
    if (!envValue.isNullOrEmpty()) return envValue
    if (askFlag) return promptFn() ?: ""
    return ""
}

/** Map a Clikt error to our process exit code: help/message output is clean (0), usage errors are 2. */
fun exitCodeFor(e: CliktError): Int = when (e) {
    is UsageError -> 2
    is PrintHelpMessage, is PrintMessage, is PrintCompletionMessage -> 0
    else -> e.statusCode
}

/** A Node syncs itself; any other object contributes its Node descendants. */
fun selectNodes(root: AbstractObject): List<AbstractObject> =
    if (root is Node) listOf(root)
    else root.getAllChildren(AbstractObject.OBJECT_NODE).sortedBy { it.objectId }

private fun promptPassword(): String? {
    val console = System.console() ?: return null
    val chars = console.readPassword("Password: ") ?: return null
    return String(chars)
}

class SyncCommand : CliktCommand(name = "nxsync") {
    override fun help(context: Context) =
        "One-way sync of agent files from NetXMS nodes into a local directory. " +
            "SOURCE must be a directory on the agent under a FILEMGR root. Local files are never deleted."

    private val server by argument(help = "NetXMS server address")
    private val source by argument(help = "Source directory on the agent (under a FILEMGR root)")
    private val destination by argument(help = "Local destination directory")

    private val objectId by option("-o", "--object", help = "Root object id (Node or container)").long().default(2)
    private val user by option("-u", "--user", help = "Login name").default("admin")
    private val password by option("-p", "--password", help = "Password (or set NXSYNC_PASSWORD)")
    private val askPassword by option("--ask-password", help = "Prompt for the password interactively").flag()
    private val verbose by option("-v", "--verbose", help = "Verbose per-file output").flag()
    private val parallel by option("--parallel", help = "Nodes to sync concurrently").int().restrictTo(min = 1).default(4)
    private val dryRun by option("--dry-run", help = "Report actions without downloading").flag()

    var exitCode: Int = 0
        private set

    override fun run() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", if (verbose) "info" else "warn")
        val session = NXCSession(server)
        exitCode = try {
            runSync(session)
        } finally {
            try {
                session.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun runSync(session: NXCSession): Int {
        try {
            session.connect()
            session.login(user, resolvePassword(password, System.getenv("NXSYNC_PASSWORD"), askPassword, ::promptPassword))
            session.syncObjects()
        } catch (e: Exception) {
            System.err.println("nxsync: connection failed: ${e.message}")
            return 2
        }

        val root = session.findObjectById(objectId)
        if (root == null) {
            System.err.println("nxsync: object $objectId not found")
            return 2
        }

        val nodes = selectNodes(root)
        if (nodes.isEmpty()) {
            System.err.println("nxsync: warning: '${root.objectName}' contains no nodes; nothing to do")
            return 0
        }

        val dest = Paths.get(destination)
        val reports = runBlocking {
            val gate = Semaphore(parallel)
            nodes.map { node ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            SyncEngine(session, source, dest, dryRun, verbose).sync(node)
                        } catch (e: Exception) {
                            NodeReport(node.objectName).apply { error("sync aborted: ${e.message}") }
                        }
                    }
                }
            }.awaitAll()
        }

        printSummary(reports)
        return if (reports.all { it.ok }) 0 else 1
    }

    private fun printSummary(reports: List<NodeReport>) {
        println()
        println("Summary:")
        for (r in reports) {
            val status = if (r.ok) "ok" else "FAILED"
            println("  ${r.nodeName}: downloaded=${r.downloaded} updated=${r.updated} skipped=${r.skipped} errors=${r.errors.size} [$status]")
            for (e in r.errors) {
                System.err.println("  ${r.nodeName}: $e")
            }
        }
    }
}

fun main(args: Array<String>) {
    val command = SyncCommand()
    val code = try {
        command.parse(args)
        command.exitCode
    } catch (e: CliktError) {
        command.echoFormattedHelp(e)
        exitCodeFor(e)
    }
    exitProcess(code)
}
