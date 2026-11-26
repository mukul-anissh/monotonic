package com.monotonic.metronome.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRoomQrCodecTest {

    @Test
    fun testRoundTripRoomInviteForLanAndCloudUrls() {
        val lanUrl = "ws://10.145.118.121:6969/ws"
        val encodedLan = SyncRoomQrCodec.encodeRoomInvite(lanUrl, "ab12cd")
        assertTrue(encodedLan.startsWith("syncmetro://join?"))

        val parsedLan = SyncRoomQrCodec.parse(encodedLan)
        assertEquals(QrParseResult.RoomInvite(lanUrl, "AB12CD"), parsedLan)

        val cloudUrl = "wss://metro.example.com/custom?room=1"
        val encodedCloud = SyncRoomQrCodec.encodeRoomInvite(cloudUrl, "XYZ789")
        val parsedCloud = SyncRoomQrCodec.parse(encodedCloud)
        assertEquals(QrParseResult.RoomInvite(cloudUrl, "XYZ789"), parsedCloud)
    }

    @Test
    fun testPlainWebSocketUrlsParseAsServerOnly() {
        assertEquals(
            QrParseResult.ServerOnly("ws://192.168.1.42:8000/ws"),
            SyncRoomQrCodec.parse("  ws://192.168.1.42:8000  ")
        )
        assertEquals(
            QrParseResult.ServerOnly("wss://metro.example.com/ws"),
            SyncRoomQrCodec.parse("wss://metro.example.com/ws")
        )
    }

    @Test
    fun testUnrelatedAndMalformedQrPayloadsAreRejected() {
        assertTrue(SyncRoomQrCodec.parse("") is QrParseResult.Invalid)
        assertTrue(SyncRoomQrCodec.parse("https://example.com") is QrParseResult.Invalid)
        assertTrue(SyncRoomQrCodec.parse("WIFI:T:WPA;S:BandRoom;P:secret;;") is QrParseResult.Invalid)
        assertTrue(
            SyncRoomQrCodec.parse("syncmetro://other?url=ws%3A%2F%2F10.0.0.1%3A8000%2Fws&code=ABC123")
                is QrParseResult.Invalid
        )
        assertTrue(
            SyncRoomQrCodec.parse("syncmetro://join?url=ws%3A%2F%2F10.0.0.1%3A8000%2Fws&code=TOO_LONG")
                is QrParseResult.Invalid
        )
        assertTrue(
            SyncRoomQrCodec.parse("syncmetro://join?code=ABC123")
                is QrParseResult.Invalid
        )
        assertTrue(
            SyncRoomQrCodec.parse("syncmetro://join?url=not-a-valid-host&code=ABC123")
                is QrParseResult.Invalid
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testEncodeRejectsInvalidRoomCode() {
        SyncRoomQrCodec.encodeRoomInvite("ws://192.168.1.5:8000/ws", "123")
    }
}
