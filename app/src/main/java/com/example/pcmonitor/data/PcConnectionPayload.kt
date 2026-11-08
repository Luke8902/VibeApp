package com.example.pcmonitor.data

import com.google.gson.Gson
import java.net.URI

data class PcConnectionPayload(val address: String, val token: String) {
    companion object {
        private data class QrPayload(
            val type: String?,
            val version: Int?,
            val url: String?,
            val token: String?,
        )

        fun parse(contents: String): PcConnectionPayload {
            val payload = runCatching { Gson().fromJson(contents, QrPayload::class.java) }
                .getOrElse { throw IllegalArgumentException("The PC connection QR is not valid JSON.", it) }
            require(payload.type == "vibe-pc-connection") {
                "This QR code is not a VIBE PC connection code."
            }
            require(payload.version == 1) { "This PC connection QR version is not supported." }

            val address = payload.url.orEmpty()
            val token = payload.token.orEmpty()
            val uri = runCatching { URI(address) }.getOrNull()
            require(uri?.scheme == "ws" && !uri.host.isNullOrBlank() && uri.path == "/ws") {
                "The PC connection QR contains an invalid address."
            }
            require(token.length >= 32 && token.length <= 256 && token.none(Char::isWhitespace)) {
                "The PC connection QR contains an invalid token."
            }
            return PcConnectionPayload(address, token)
        }
    }
}
