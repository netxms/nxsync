package org.netxms.sync

import com.xenomachina.argparser.ArgParser
import com.xenomachina.argparser.default
import com.xenomachina.argparser.mainBody
import org.netxms.client.NXCObjectModificationData
import org.netxms.client.NXCSession
import org.netxms.client.objects.AbstractObject
import org.netxms.client.objects.Node
import org.netxms.client.server.AgentFile
import java.io.File

class Args(parser: ArgParser) {
    val v by parser.flagging("-v", "--verbose", help = "Verbose mode")

    val objectId by parser.storing("-o", "--object", help = "Object ID to sync") { toLong() }.default(2)
    val user by parser.storing("-u", "--user", help = "Username").default("admin")
    val commandLinePassword by parser.storing("-p", "--password", help = "Password").default("")
    val askPassword by parser.flagging("--ask-password", help = "Ask for password")

    val server by parser.positional("SERVER", help = "Server address").default("localhost")
    val source by parser.positional("SOURCE", help = "Source file/directory")
    val destination by parser.positional("DESTINATION", "Destination directory")

    var password: String = ""
}

class NXSync {
    fun sync() {
    }
}

fun main(args: Array<String>) = mainBody {
    val config = ArgParser(args).parseInto(::Args)
    config.run {
        if (askPassword) {
            print("Password: ")
            password = (System.console()?.readPassword("Password: ") ?: readLine()).toString()
        } else {
            password = commandLinePassword
        }

//        if (v) {
//            logger.info { "Verbose mode enabled" }
//        }
    }

    val session = NXCSession(config.server)
    session.connect()
    session.login(config.user, config.password)
    session.syncObjects()

    session.findObjectById(config.objectId)?.let { obj ->
        if (obj is Node) {
            sync(session, obj, config.source, config.destination)
        } else {
            obj.getAllChildren(AbstractObject.OBJECT_NODE).forEach { child ->
                try {
                    sync(session, child, config.source, config.destination)
                } catch (e: Exception) {
                    println("Error syncing object ${child.objectName}: ${e.message}")
                }
            }
        }
    } ?: println("Object with ID=${config.objectId} not found")

    session.disconnect()
}

fun sync(session: NXCSession, node: AbstractObject, source: String, destination: String) {
    val sanitizedNodeName = node.objectName.replace("[^a-zA-Z0-9-_\\\\.]".toRegex(), "_").trim('_')
    val syncDestination = File("$destination/${node.objectId}-$sanitizedNodeName")
    if (!syncDestination.exists()) {
        syncDestination.mkdirs()
    }

    val potentialRoots = session.listAgentFiles(null, "/", node.objectId).filter {
        source.startsWith(it.fullName)
    }

    if (potentialRoots.isEmpty()) {
        println("No potential roots found for \"$source\", please check FILEMGR section in the agent configuration.")
        return
    }
    val root = potentialRoots.first()

    val scanRoot = AgentFile(source, AgentFile.DIRECTORY, null, node.objectId)

    val files = scanFiles(session, scanRoot, node.objectId, source)

    println("Files to sync: ${files.size}")

    for (file in files) {
//        val filePath = file.filePath.removePrefix(root.filePath)
        val filePath = file.filePath.removePrefix(source)
        val targetFile = File(syncDestination, filePath.replace("\\", File.separator))

        if (targetFile.exists()) {
            println("File $targetFile already exists, skipping")
            continue
        }

        println("Downloading ${file.filePath} to $targetFile")

        try {
            val fileData = session.downloadFileFromAgent(node.objectId, file.fullName, 0, false, null)
            fileData?.let { data ->
                targetFile.parentFile.mkdirs()
                targetFile.writeBytes(data.file.readBytes())
            }
        } catch (e: Exception) {
            println("Error downloading file: ${e.message}")
        }
    }
}

fun scanFiles(
    session: NXCSession, root: AgentFile, objectId: Long, source: String, level: Int = 0
): MutableList<AgentFile> {
    val files: MutableList<AgentFile> = arrayListOf()

    println("Scanning ${root.filePath}")

    try {
        session.listAgentFiles(root, root.filePath, objectId).forEach {
            if (it.isDirectory) {
                files.addAll(scanFiles(session, it, objectId, source, level + 1))
            } else {
                if (it.filePath.startsWith(source)) {
                    files.add(it)
                }
            }
        }
    } catch (e: Exception) {
        println("Error processing ${root.filePath}: ${e.message}")
    }

    return files
}
