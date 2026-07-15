package com.retryjournal.queue

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LifecycleGateTest {

    private val gate = LifecycleGate(
        closedMessage = "closed",
        closeWhileBusyMessage = "busy",
    )

    @Test
    fun `close rejects new enter after the gate is closed`() {
        runBlocking {
            gate.close()
            assertFailsWith<IllegalStateException> { gate.enter() }
        }
    }

    @Test
    fun `close fails while a session is active and succeeds once it leaves`() {
        runBlocking {
            gate.enter()
            assertFailsWith<IllegalStateException> { gate.close() }
            gate.leave()
            gate.close()
            assertFailsWith<IllegalStateException> { gate.enter() }
        }
    }

    @Test
    fun `hasActiveSessions tracks enter and leave`() {
        runBlocking {
            assertFalse(gate.hasActiveSessions())
            gate.enter()
            assertTrue(gate.hasActiveSessions())
            gate.leave()
            assertFalse(gate.hasActiveSessions())
        }
    }

    @Test
    fun `close is idempotent`() {
        runBlocking {
            gate.close()
            gate.close()
            assertFailsWith<IllegalStateException> { gate.enter() }
        }
    }
}
