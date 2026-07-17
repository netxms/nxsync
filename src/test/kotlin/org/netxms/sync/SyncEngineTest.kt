package org.netxms.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SyncEngineTest {

    private val work = Files.createTempDirectory("nxsync-test")

    @AfterTest
    fun cleanup() {
        work.toFile().deleteRecursively()
    }

    @Test
    fun stageAndCommitWritesContentCreatesParentsAndStampsMtime() {
        val temp = Files.createTempFile(work, "src", ".tmp")
        Files.write(temp, "hello".toByteArray())
        val target = work.resolve("sub/dir/out.txt")
        val remoteMtimeMs = 1_700_000_000_000L

        stageAndCommit(temp, target, remoteMtimeMs)

        assertEquals("hello", Files.readString(target))
        assertFalse(Files.exists(temp), "source temp should be consumed")
        assertFalse(Files.exists(target.resolveSibling("out.txt.nxsync-tmp")), "staging file must be cleaned up")
        assertEquals(remoteMtimeMs / 1000, Files.getLastModifiedTime(target).toMillis() / 1000)
    }

    /** The core "true sync" invariant: a committed file's mtime makes a second run skip it. */
    @Test
    fun committedFileIsSkippedOnSecondRun() {
        val temp = Files.createTempFile(work, "src", ".tmp")
        Files.write(temp, "payload".toByteArray())
        val target = work.resolve("out.txt")
        val remoteMtimeMs = 1_700_000_000_000L
        val remoteSize = Files.size(temp)

        stageAndCommit(temp, target, remoteMtimeMs)

        val localSize = Files.size(target)
        val localMtimeMs = Files.getLastModifiedTime(target).toMillis()
        assertEquals(Action.SKIP, decide(true, localSize, localMtimeMs, remoteSize, remoteMtimeMs))
    }

    @Test
    fun stageAndCommitReplacesExistingTarget() {
        val target = work.resolve("out.txt")
        Files.write(target, "old".toByteArray())
        val temp = Files.createTempFile(work, "src", ".tmp")
        Files.write(temp, "new".toByteArray())

        stageAndCommit(temp, target, 1_700_000_000_000L)

        assertEquals("new", Files.readString(target))
    }

    /** A local file that happens to look like a staging name must never be touched. */
    @Test
    fun stageAndCommitPreservesUnrelatedLocalFiles() {
        val target = work.resolve("out.txt")
        val bystander = work.resolve("out.txt.nxsync-tmp")
        Files.write(bystander, "keep me".toByteArray())
        val temp = Files.createTempFile(work, "src", ".tmp")
        Files.write(temp, "payload".toByteArray())

        stageAndCommit(temp, target, 1_700_000_000_000L)

        assertEquals("payload", Files.readString(target))
        assertEquals("keep me", Files.readString(bystander))
    }
}
