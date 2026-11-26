package com.monotonic.metronome.network

import java.net.URI
import java.util.Locale

object ServerUrlNormalizer {

    const val DEFAULT_PORT = 8000
    const val DEFAULT_PATH = "/ws"

    private val DNS_LABEL_REGEX = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

    private data class ParsedAuthority(
        val host: String,
        val port: Int?,
        val isIpv4: Boolean,
        val isIpv6: Boolean
    )

    fun normalize(raw: String, defaultPort: Int = DEFAULT_PORT): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() } || '#' in trimmed) {
            return null
        }

        val schemeSepIdx = trimmed.indexOf("://")
        val targetScheme: String
        val remainder: String
        val hasExplicitScheme: Boolean

        if (schemeSepIdx >= 0) {
            hasExplicitScheme = true
            val rawScheme = trimmed.substring(0, schemeSepIdx).lowercase(Locale.US)
            targetScheme = when (rawScheme) {
                "ws", "http" -> "ws"
                "wss", "https" -> "wss"
                else -> return null
            }
            remainder = trimmed.substring(schemeSepIdx + 3)
            if (remainder.isEmpty()) return null
        } else {
            hasExplicitScheme = false
            // Reject scheme-like prefixes without "://" (e.g., "ws:1.2.3.4", ":8000")
            if (trimmed.startsWith(":")) return null
            val firstSlashOrQuery = trimmed.indexOfFirst { it == '/' || it == '?' }
            val authorityCandidate = if (firstSlashOrQuery >= 0) {
                trimmed.substring(0, firstSlashOrQuery)
            } else {
                trimmed
            }
            if (!authorityCandidate.startsWith("[")) {
                val firstColon = authorityCandidate.indexOf(':')
                if (firstColon > 0) {
                    val afterColon = authorityCandidate.substring(firstColon + 1)
                    if (!afterColon.all { it.isDigit() }) {
                        return null
                    }
                }
            }
            remainder = trimmed
            targetScheme = ""
        }

        val splitIdx = remainder.indexOfFirst { it == '/' || it == '?' }
        val authorityStr = if (splitIdx >= 0) remainder.substring(0, splitIdx) else remainder
        val suffixStr = if (splitIdx >= 0) remainder.substring(splitIdx) else ""

        if (authorityStr.isEmpty() || '@' in authorityStr) {
            return null
        }

        val authority = parseAuthority(authorityStr) ?: return null

        val resolvedScheme: String
        val resolvedPort: Int?

        if (hasExplicitScheme) {
            resolvedScheme = targetScheme
            resolvedPort = authority.port
        } else {
            // Bare single-word inputs without port (e.g. "hello") are rejected unless localhost or IP
            if (authority.port == null &&
                !authority.isIpv4 &&
                !authority.isIpv6 &&
                !authority.host.equals("localhost", ignoreCase = true) &&
                '.' !in authority.host
            ) {
                return null
            }

            val isLocalOrIp = authority.isIpv4 ||
                authority.isIpv6 ||
                authority.host.equals("localhost", ignoreCase = true) ||
                authority.host.endsWith(".local", ignoreCase = true) ||
                '.' !in authority.host

            if (isLocalOrIp) {
                resolvedScheme = "ws"
                resolvedPort = authority.port ?: defaultPort
            } else {
                resolvedScheme = "wss"
                resolvedPort = authority.port
            }
        }

        val portPart = if (resolvedPort != null) ":$resolvedPort" else ""
        val candidateUrl = "$resolvedScheme://${authority.host}$portPart$suffixStr"

        val uri = try {
            URI(candidateUrl)
        } catch (_: Exception) {
            return null
        }

        val uriHost = uri.host ?: return null
        if (uriHost.isBlank() || uri.fragment != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null

        val rawPath = uri.rawPath
        val normalizedPath = if (rawPath.isNullOrEmpty() || rawPath == "/") {
            DEFAULT_PATH
        } else {
            rawPath
        }
        val querySuffix = uri.rawQuery?.let { "?$it" } ?: ""

        return "$resolvedScheme://${authority.host}$portPart$normalizedPath$querySuffix"
    }

    fun extractHostDisplay(wsUrl: String): String {
        val trimmed = wsUrl.trim()
        if (trimmed.isEmpty()) return ""
        return try {
            val uri = URI(trimmed)
            val host = uri.host
            if (host.isNullOrBlank()) {
                trimmed
            } else if (uri.port > 0) {
                "$host:${uri.port}"
            } else {
                host
            }
        } catch (_: Exception) {
            trimmed
        }
    }

    fun isRemoteSecureUrl(wsUrl: String): Boolean {
        return wsUrl.trim().startsWith("wss://", ignoreCase = true)
    }

    fun isLanOrLocalUrl(wsUrl: String): Boolean {
        val trimmed = wsUrl.trim()
        if (!trimmed.startsWith("ws://", ignoreCase = true)) {
            return false
        }
        val host = try {
            URI(trimmed).host?.lowercase(Locale.US)
        } catch (_: Exception) {
            null
        } ?: return false

        if (host == "localhost" || host.endsWith(".local")) {
            return true
        }
        val unbracketed = host.removePrefix("[").removeSuffix("]")
        if (unbracketed == "::1") {
            return true
        }

        val octets = parseIpv4Octets(unbracketed) ?: return false
        val first = octets[0]
        val second = octets[1]
        return first == 10 ||
            first == 127 ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun parseAuthority(authority: String): ParsedAuthority? {
        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            val host = authority.substring(0, closeBracket + 1)
            val inner = authority.substring(1, closeBracket)
            if (':' !in inner || !inner.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) {
                return null
            }
            val rest = authority.substring(closeBracket + 1)
            val port = if (rest.isEmpty()) {
                null
            } else if (rest.startsWith(":")) {
                parseValidPort(rest.substring(1)) ?: return null
            } else {
                return null
            }
            return ParsedAuthority(host = host, port = port, isIpv4 = false, isIpv6 = true)
        }

        val colonCount = authority.count { it == ':' }
        if (colonCount > 1) return null

        val host: String
        val port: Int?
        if (colonCount == 1) {
            val idx = authority.indexOf(':')
            host = authority.substring(0, idx)
            port = parseValidPort(authority.substring(idx + 1)) ?: return null
        } else {
            host = authority
            port = null
        }

        if (host.isEmpty()) return null

        val segments = host.split('.')
        if (segments.any { it.isEmpty() }) return null

        val allNumeric = segments.all { seg -> seg.all { it.isDigit() } }
        if (allNumeric) {
            if (parseIpv4Octets(host) == null) return null
            return ParsedAuthority(host = host, port = port, isIpv4 = true, isIpv6 = false)
        }

        if (segments.any { !DNS_LABEL_REGEX.matches(it) }) {
            return null
        }
        // Reject hostnames whose final TLD label is purely numeric (e.g. "foo.123")
        if (segments.last().all { it.isDigit() }) {
            return null
        }

        return ParsedAuthority(host = host, port = port, isIpv4 = false, isIpv6 = false)
    }

    private fun parseValidPort(rawPort: String): Int? {
        if (rawPort.isEmpty() || !rawPort.all { it.isDigit() }) return null
        val port = rawPort.toIntOrNull() ?: return null
        return if (port in 1..65535) port else null
    }

    private fun parseIpv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }
}
