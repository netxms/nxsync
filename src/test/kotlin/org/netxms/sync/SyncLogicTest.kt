package org.netxms.sync

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncLogicTest {

    @Test
    fun decideTable() {
        data class Case(
            val name: String,
            val exists: Boolean,
            val localSize: Long,
            val localMtime: Long,
            val remoteSize: Long,
            val remoteMtime: Long,
            val expected: Action,
        )

        val cases = listOf(
            Case("missing -> download", false, 0, 0, 100, 1000, Action.DOWNLOAD),
            Case("size differs -> update", true, 50, 1000, 100, 1000, Action.UPDATE),
            Case("mtime differs -> update", true, 100, 1000, 100, 5000, Action.UPDATE),
            Case("identical -> skip", true, 100, 1000, 100, 1000, Action.SKIP),
            Case("sub-second mtime diff -> skip", true, 100, 1200, 100, 1800, Action.SKIP),
            Case("mtime crosses second boundary -> update", true, 100, 1999, 100, 2000, Action.UPDATE),
        )

        for (c in cases) {
            assertEquals(
                c.expected,
                decide(c.exists, c.localSize, c.localMtime, c.remoteSize, c.remoteMtime),
                c.name,
            )
        }
    }

    @Test
    fun sanitizeNodeNameCases() {
        assertEquals("host-01.example.com", sanitizeNodeName("host-01.example.com"))
        assertEquals("a_b", sanitizeNodeName("a\\b"))
        assertEquals("a_b", sanitizeNodeName("a/b"))
        assertEquals("node_name", sanitizeNodeName("node name"))
        assertEquals("node", sanitizeNodeName(""))
        assertEquals("node", sanitizeNodeName("///"))
        assertEquals("node", sanitizeNodeName("日本語"))
        assertEquals("caf", sanitizeNodeName("café")) // unicode 'é' replaced with '_' then trimmed
        assertEquals("node", sanitizeNodeName("__"))
    }

    @Test
    fun relativePathCases() {
        assertEquals("dir/file.txt", relativePath("/etc/dir/file.txt", "/etc"))
        assertEquals("file.txt", relativePath("/etc/file.txt", "/etc"))
        assertEquals("a/b/c.txt", relativePath("C:\\data\\a\\b\\c.txt", "C:\\data"))
        assertEquals("file.txt", relativePath("/etc//file.txt", "/etc"))
        assertEquals("x/y", relativePath("\\x\\y", ""))
    }

    @Test
    fun resolveTargetWithin() {
        val nodeDir = Paths.get("/dest/1-node")
        val target = resolveTarget(nodeDir, "sub/file.txt")
        assertEquals(Paths.get("/dest/1-node/sub/file.txt"), target)
    }

    @Test
    fun resolveTargetEscapeRejected() {
        val nodeDir = Paths.get("/dest/1-node")
        assertNull(resolveTarget(nodeDir, "../../etc/passwd"))
        assertNull(resolveTarget(nodeDir, "../2-other/file.txt"))
    }

    @Test
    fun resolveTargetNormalizesInternalDotDot() {
        val nodeDir = Paths.get("/dest/1-node")
        assertEquals(Paths.get("/dest/1-node/b.txt"), resolveTarget(nodeDir, "a/../b.txt"))
    }

    @Test
    fun isUnderRootBoundaries() {
        assertTrue(isUnderRoot("/opt/data/x", "/opt/data"))
        assertTrue(isUnderRoot("/opt/data", "/opt/data"))
        assertTrue(isUnderRoot("/opt/data/", "/opt/data"))
        assertFalse(isUnderRoot("/opt/database", "/opt/data"))
        assertTrue(isUnderRoot("/anything", "/"))
        assertTrue(isUnderRoot("C:\\data\\x", "C:\\data"))
        assertFalse(isUnderRoot("/opt/data", "/opt/data/sub"))
    }
}
