package com.example.pcmonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcConnectionPayloadTest {
    @Test
    fun parsesPcPairingQr() {
        val payload = PcConnectionPayload.parse(
            """{"type":"vibe-pc-connection","version":1,"url":"ws://192.168.1.20:8765/ws","token":"${"a".repeat(43)}"}""",
        )

        assertEquals("ws://192.168.1.20:8765/ws", payload.address)
        assertEquals("a".repeat(43), payload.token)
    }

    @Test
    fun rejectsUnrelatedQr() {
        assertThrows(IllegalArgumentException::class.java) {
            PcConnectionPayload.parse("""{"type":"other","version":1}""")
        }
    }

    @Test
    fun rejectsNonLanAddressSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            PcConnectionPayload.parse(
                """{"type":"vibe-pc-connection","version":1,"url":"https://example.com/ws","token":"${"a".repeat(43)}"}""",
            )
        }
    }
}
