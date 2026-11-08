package com.example.pcmonitor

import com.example.pcmonitor.lyrics.LrcParser
import org.junit.Assert.assertEquals
import org.junit.Test

class LrcParserTest {
    @Test fun parsesAndSortsTimestampedLines() {
        assertEquals(listOf(12340L, 62000L), LrcParser.parse("[01:02]Later\n[00:12.34]First").map { it.timeMs })
    }

    @Test fun handlesMultipleTimestampsAndEmptyLyrics() {
        val parsed = LrcParser.parse("[00:01.0][00:02.00]Repeat\n[ar:Artist]")
        assertEquals(listOf(1000L, 2000L), parsed.map { it.timeMs })
        assertEquals(listOf("Repeat", "Repeat"), parsed.map { it.text })
    }
}
