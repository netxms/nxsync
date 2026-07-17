package org.netxms.sync

import com.github.ajalt.clikt.core.Abort
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import org.netxms.base.NXCPMessage
import org.netxms.client.objects.Container
import org.netxms.client.objects.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainLogicTest {

    @Test
    fun passwordOptionWins() {
        assertEquals(
            "explicit",
            resolvePassword("explicit", "fromenv", askFlag = true) { "prompted" },
        )
    }

    @Test
    fun passwordFallsBackToEnv() {
        assertEquals(
            "fromenv",
            resolvePassword(null, "fromenv", askFlag = true) { "prompted" },
        )
    }

    @Test
    fun passwordEmptyEnvIsIgnored() {
        assertEquals(
            "prompted",
            resolvePassword(null, "", askFlag = true) { "prompted" },
        )
    }

    @Test
    fun passwordPromptsWhenAsked() {
        assertEquals(
            "prompted",
            resolvePassword(null, null, askFlag = true) { "prompted" },
        )
    }

    @Test
    fun passwordDefaultsToEmpty() {
        assertEquals(
            "",
            resolvePassword(null, null, askFlag = false) { "prompted" },
        )
    }

    @Test
    fun passwordEmptyWhenPromptCancelled() {
        assertEquals(
            "",
            resolvePassword(null, null, askFlag = true) { null },
        )
    }

    @Test
    fun exitCodeMapping() {
        assertEquals(2, exitCodeFor(UsageError("bad usage")))
        assertEquals(0, exitCodeFor(PrintMessage("help text")))
        assertEquals(1, exitCodeFor(Abort()))
    }

    @Test
    fun selectNodesReturnsNodeItself() {
        val node = Node(1L, null)
        val selected = selectNodes(node)
        assertEquals(1, selected.size)
        assertTrue(selected[0] === node)
    }

    @Test
    fun selectNodesEmptyContainerYieldsNothing() {
        val container = Container(NXCPMessage(0), null)
        assertTrue(selectNodes(container).isEmpty())
    }
}
