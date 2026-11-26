package com.monotonic.metronome.network

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed class QrParseResult {
    data class RoomInvite(val serverUrl: String, val roomCode: String) : QrParseResult()
    data class ServerOnly(val serverUrl: String) : QrParseResult()
    data class Invalid(val reason: String) : QrParseResult()
}

object SyncRoomQrCodec {

    const val SCHEME = "syncmetro"
    const val JOIN_HOST = "join"
    private val ROOM_CODE_REGEX = Regex("^[A-Z0-9]{6}$")
    private val UTF_8_NAME: String = StandardCharsets.UTF_8.name()

    fun encodeRoomInvite(serverUrl: String, roomCode: String): String {
        val normalizedUrl = ServerUrlNormalizer.normalize(serverUrl)
            ?: throw IllegalArgumentException("Invalid server URL: $serverUrl")
        val cleanCode = roomCode.trim().uppercase(Locale.US)
        if (!ROOM_CODE_REGEX.matches(cleanCode)) {
            throw IllegalArgumentException("Room code must be 6 alphanumeric characters")
        }
        val encodedUrl = URLEncoder.encode(normalizedUrl, UTF_8_NAME)
        return "$SCHEME://$JOIN_HOST?url=$encodedUrl&code=$cleanCode"
    }

    fun parse(rawPayload: String): QrParseResult {
        val trimmed = rawPayload.trim()
        if (trimmed.isEmpty()) {
            return QrParseResult.Invalid("Empty QR code")
        }

        if (trimmed.startsWith("$SCHEME://", ignoreCase = true)) {
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                return QrParseResult.Invalid("Malformed Monotonic QR link")
            }
            if (!uri.host.equals(JOIN_HOST, ignoreCase = true)) {
                return QrParseResult.Invalid("Unsupported Monotonic QR action")
            }
            val rawQuery = uri.rawQuery
                ?: return QrParseResult.Invalid("QR code is missing room details")

            val params = parseQueryParams(rawQuery)
                ?: return QrParseResult.Invalid("Malformed room QR parameters")

            val rawServerUrl = params["url"]
                ?: return QrParseResult.Invalid("QR code is missing server address")
            val normalizedUrl = ServerUrlNormalizer.normalize(rawServerUrl)
                ?: return QrParseResult.Invalid("Invalid server address in QR code")

            val rawCode = params["code"]?.trim()?.uppercase(Locale.US).orEmpty()
            if (!ROOM_CODE_REGEX.matches(rawCode)) {
                return QrParseResult.Invalid("Invalid room code in QR code")
            }

            return QrParseResult.RoomInvite(serverUrl = normalizedUrl, roomCode = rawCode)
        }

        if (trimmed.startsWith("ws://", ignoreCase = true) ||
            trimmed.startsWith("wss://", ignoreCase = true)
        ) {
            val normalizedUrl = ServerUrlNormalizer.normalize(trimmed)
                ?: return QrParseResult.Invalid("Invalid server URL in QR code")
            return QrParseResult.ServerOnly(serverUrl = normalizedUrl)
        }

        return QrParseResult.Invalid("Not a Monotonic sync room QR code")
    }

    private fun parseQueryParams(rawQuery: String): Map<String, String>? {
        val result = mutableMapOf<String, String>()
        val pairs = rawQuery.split('&')
        for (pair in pairs) {
            if (pair.isEmpty()) continue
            val eqIdx = pair.indexOf('=')
            if (eqIdx <= 0) continue
            val rawKey = pair.substring(0, eqIdx)
            val rawValue = pair.substring(eqIdx + 1)
            val key = try {
                URLDecoder.decode(rawKey, UTF_8_NAME).lowercase(Locale.US)
            } catch (_: Exception) {
                return null
            }
            val value = try {
                URLDecoder.decode(rawValue, UTF_8_NAME)
            } catch (_: Exception) {
                return null
            }
            result[key] = value
        }
        return result
    }
}
