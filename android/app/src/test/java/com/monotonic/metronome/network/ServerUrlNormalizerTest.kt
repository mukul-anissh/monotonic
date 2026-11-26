package com.monotonic.metronome.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlNormalizerTest {

    @Test
    fun testPromptExamplesNormalizeCorrectly() {
        assertEquals(
            "ws://10.145.118.121:6969/ws",
            ServerUrlNormalizer.normalize("10.145.118.121:6969")
        )
        assertEquals(
            "ws://192.168.1.12:8000/ws",
            ServerUrlNormalizer.normalize("192.168.1.12")
        )
        assertEquals(
            "wss://metro.example.com/ws",
            ServerUrlNormalizer.normalize("metro.example.com")
        )
        assertEquals(
            "wss://metro.example.com/ws",
            ServerUrlNormalizer.normalize("https://metro.example.com")
        )
        assertEquals(
            "ws://10.0.0.5:6969/ws",
            ServerUrlNormalizer.normalize("http://10.0.0.5:6969")
        )
    }

    @Test
    fun testAlreadyValidWebSocketUrlsAndCustomPathsStayIntact() {
        assertEquals(
            "ws://10.0.0.5:6969/ws",
            ServerUrlNormalizer.normalize("  ws://10.0.0.5:6969/ws  ")
        )
        assertEquals(
            "wss://metro.example.com/custom-ws?token=abc",
            ServerUrlNormalizer.normalize("wss://metro.example.com/custom-ws?token=abc")
        )
        assertEquals(
            "wss://metro.example.com:8443/ws",
            ServerUrlNormalizer.normalize("https://metro.example.com:8443/")
        )
        assertEquals(
            "ws://localhost:8000/ws",
            ServerUrlNormalizer.normalize("localhost")
        )
        assertEquals(
            "ws://studio-mac.local:8000/ws",
            ServerUrlNormalizer.normalize("studio-mac.local")
        )
        assertEquals(
            "ws://my-laptop:6969/ws",
            ServerUrlNormalizer.normalize("my-laptop:6969")
        )
    }

    @Test
    fun testInvalidOrMalformedInputsReturnNull() {
        assertNull(ServerUrlNormalizer.normalize(""))
        assertNull(ServerUrlNormalizer.normalize("   "))
        assertNull(ServerUrlNormalizer.normalize("ws://192.168.1.1 8000"))
        assertNull(ServerUrlNormalizer.normalize("ftp://192.168.1.1:8000"))
        assertNull(ServerUrlNormalizer.normalize("syncmetro://join?code=ABC123"))
        assertNull(ServerUrlNormalizer.normalize("ws://"))
        assertNull(ServerUrlNormalizer.normalize(":8000"))
        assertNull(ServerUrlNormalizer.normalize("999.168.1.1"))
        assertNull(ServerUrlNormalizer.normalize("192.168.1"))
        assertNull(ServerUrlNormalizer.normalize("192.168.1.1:0"))
        assertNull(ServerUrlNormalizer.normalize("192.168.1.1:70000"))
        assertNull(ServerUrlNormalizer.normalize("192.168.1.1:abc"))
        assertNull(ServerUrlNormalizer.normalize("hello"))
        assertNull(ServerUrlNormalizer.normalize("ws://192.168.1.1:8000/ws#frag"))
    }

    @Test
    fun testHostDisplayAndLanRemotePredicates() {
        assertEquals(
            "10.145.118.121:6969",
            ServerUrlNormalizer.extractHostDisplay("ws://10.145.118.121:6969/ws")
        )
        assertEquals(
            "metro.example.com",
            ServerUrlNormalizer.extractHostDisplay("wss://metro.example.com/ws")
        )

        assertTrue(ServerUrlNormalizer.isRemoteSecureUrl("wss://metro.example.com/ws"))
        assertFalse(ServerUrlNormalizer.isRemoteSecureUrl("ws://192.168.1.5:8000/ws"))

        assertTrue(ServerUrlNormalizer.isLanOrLocalUrl("ws://192.168.1.100:8000/ws"))
        assertTrue(ServerUrlNormalizer.isLanOrLocalUrl("ws://10.145.118.121:6969/ws"))
        assertTrue(ServerUrlNormalizer.isLanOrLocalUrl("ws://172.20.10.2:8000/ws"))
        assertTrue(ServerUrlNormalizer.isLanOrLocalUrl("ws://localhost:8000/ws"))
        assertTrue(ServerUrlNormalizer.isLanOrLocalUrl("ws://studio.local:8000/ws"))
        assertFalse(ServerUrlNormalizer.isLanOrLocalUrl("wss://metro.example.com/ws"))
        assertFalse(ServerUrlNormalizer.isLanOrLocalUrl("ws://8.8.8.8:8000/ws"))
    }
}
