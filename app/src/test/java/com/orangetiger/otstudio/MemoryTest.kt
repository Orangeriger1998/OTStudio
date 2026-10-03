package com.orangetiger.otstudio

import org.junit.Assert.*
import org.junit.Test

class MemoryTest {
    @Test fun onlyEnabledLinesOpenTheSystemPrompt() {
        assertEquals("", MemoryPrompt.build(emptyList()))
        assertEquals("", MemoryPrompt.build(listOf(MemoryLine(1, "Call the user Mark.", false))))
        val prompt = MemoryPrompt.build(listOf(MemoryLine(1, "Call the user Mark.", true), MemoryLine(2, "Off line", false), MemoryLine(3, "Do not use em dashes in your responses.", true)))
        assertTrue(prompt.startsWith("The user set these standing instructions."))
        assertTrue(prompt.endsWith("- Call the user Mark.\n- Do not use em dashes in your responses."))
        assertFalse(prompt.contains("Off line"))
    }

    @Test fun linesAreCleanedAndBounded() {
        assertEquals("Be brief. Use lists.", MemoryPrompt.clean("  Be brief.\n\n  Use lists.  "))
        assertThrows(UserError::class.java) { MemoryPrompt.clean("   ") }
        assertThrows(UserError::class.java) { MemoryPrompt.clean("x".repeat(MemoryPrompt.MAX_LENGTH + 1)) }
    }

    @Test fun memoryComesFirstInTheOnDeviceWindow() {
        val system = MemoryPrompt.build(listOf(MemoryLine(1, "Call the user Mark.", true)))
        val window = ChatWindow.fit(system, listOf(Turn("user", "hi"), Turn("assistant", "hello")), "next", 4096, 512) { it.length / 4 + 1 }
        assertEquals("system", window.first().role)
        assertEquals(system, window.first().content)
        assertEquals("next", window.last().content)
    }
}
