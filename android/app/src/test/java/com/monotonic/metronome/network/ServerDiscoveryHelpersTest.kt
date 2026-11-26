package com.monotonic.metronome.network

import com.monotonic.metronome.model.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ServerDiscoveryHelpersTest {

    @Test
    fun testServiceTypeMatchingHandlesTrailingDotQuirk() {
        assertTrue(ServerDiscoveryManager.matchesServiceType("_syncmetro._tcp"))
        assertTrue(ServerDiscoveryManager.matchesServiceType("_syncmetro._tcp."))
        assertTrue(ServerDiscoveryManager.matchesServiceType("_syncmetro._tcp.local."))
        assertFalse(ServerDiscoveryManager.matchesServiceType("_http._tcp."))
        assertFalse(ServerDiscoveryManager.matchesServiceType(null))
    }

    @Test
    fun testTxtParsingAndDeduplication() {
        assertNull(ServerDiscoveryManager.parseTxtValue(null))
        assertNull(ServerDiscoveryManager.parseTxtValue("   ".toByteArray(StandardCharsets.UTF_8)))
        assertEquals(
            "StudioMac",
            ServerDiscoveryManager.parseTxtValue(" StudioMac ".toByteArray(StandardCharsets.UTF_8))
        )

        assertEquals("/ws", ServerDiscoveryManager.normalizeTxtPath(null))
        assertEquals("/ws", ServerDiscoveryManager.normalizeTxtPath("/"))
        assertEquals("/ws", ServerDiscoveryManager.normalizeTxtPath("ws"))
        assertEquals("/custom", ServerDiscoveryManager.normalizeTxtPath("/custom"))

        val s1 = DiscoveredServer(name = "B-Host", host = "192.168.1.20", port = 8000)
        val s1Dup = DiscoveredServer(name = "B-Host-2", host = "192.168.1.20", port = 8000)
        val s2 = DiscoveredServer(name = "A-Host", host = "192.168.1.10", port = 6969)

        val deduped = ServerDiscoveryManager.deduplicateServers(listOf(s1, s1Dup, s2))
        assertEquals(listOf(s2, s1), deduped)
    }

    @Test
    fun testSelectionPolicyAutoSelectsSingleLanServerAndRespectsRemoteWss() {
        val localServer = DiscoveredServer(
            name = "Rehearsal Mac",
            host = "10.145.118.121",
            port = 6969,
            path = "/ws"
        )

        // Fresh install default -> auto-select single mDNS server
        val freshOutcome = ServerSelectionPolicy.evaluate(
            discovered = listOf(localServer),
            currentServerUrl = "ws://192.168.1.100:8000/ws",
            hasExplicitSavedServer = false,
            isEditorExpanded = false,
            userManuallyPickedInSession = false,
            isInRoom = false
        )
        assertTrue(freshOutcome.autoSelected)
        assertEquals("ws://10.145.118.121:6969/ws", freshOutcome.serverUrl)
        assertEquals("Rehearsal Mac", freshOutcome.activeServerName)

        // Stale LAN IP -> auto-select single mDNS server
        val staleLanOutcome = ServerSelectionPolicy.evaluate(
            discovered = listOf(localServer),
            currentServerUrl = "ws://192.168.1.44:8000/ws",
            hasExplicitSavedServer = true,
            isEditorExpanded = false,
            userManuallyPickedInSession = false,
            isInRoom = false
        )
        assertTrue(staleLanOutcome.autoSelected)
        assertEquals("ws://10.145.118.121:6969/ws", staleLanOutcome.serverUrl)

        // Explicit remote wss:// server -> never overwrite silently
        val remoteOutcome = ServerSelectionPolicy.evaluate(
            discovered = listOf(localServer),
            currentServerUrl = "wss://metro.example.com/ws",
            hasExplicitSavedServer = true,
            isEditorExpanded = false,
            userManuallyPickedInSession = false,
            isInRoom = false
        )
        assertFalse(remoteOutcome.autoSelected)
        assertEquals("wss://metro.example.com/ws", remoteOutcome.serverUrl)
        assertNull(remoteOutcome.activeServerName)

        // Manual editor open -> do not auto-overwrite
        val editorOpenOutcome = ServerSelectionPolicy.evaluate(
            discovered = listOf(localServer),
            currentServerUrl = "ws://192.168.1.44:8000/ws",
            hasExplicitSavedServer = true,
            isEditorExpanded = true,
            userManuallyPickedInSession = false,
            isInRoom = false
        )
        assertFalse(editorOpenOutcome.autoSelected)
        assertEquals("ws://192.168.1.44:8000/ws", editorOpenOutcome.serverUrl)

        // Multiple discovered servers -> do not auto-pick
        val secondServer = DiscoveredServer(
            name = "Backup Laptop",
            host = "10.145.118.122",
            port = 8000
        )
        val multiOutcome = ServerSelectionPolicy.evaluate(
            discovered = listOf(localServer, secondServer),
            currentServerUrl = "ws://192.168.1.100:8000/ws",
            hasExplicitSavedServer = false,
            isEditorExpanded = false,
            userManuallyPickedInSession = false,
            isInRoom = false
        )
        assertFalse(multiOutcome.autoSelected)
        assertEquals("ws://192.168.1.100:8000/ws", multiOutcome.serverUrl)
    }
}
