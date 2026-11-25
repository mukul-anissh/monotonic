package com.monotonic.metronome.model

import com.monotonic.metronome.data.UserPreferencesSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStateAndSoundTest {

    @Test
    fun testClickSoundDefaultAndIds() {
        assertEquals(0, ClickSoundOption.CLASSIC.id)
        assertEquals(1, ClickSoundOption.DIGITAL_CLICK.id)
        assertEquals(2, ClickSoundOption.WOODBLOCK.id)
        assertEquals(3, ClickSoundOption.SOFT_TICK.id)

        assertEquals(ClickSoundOption.CLASSIC, ClickSoundOption.fromId(0))
        assertEquals(ClickSoundOption.DIGITAL_CLICK, ClickSoundOption.fromId(1))
        assertEquals(ClickSoundOption.WOODBLOCK, ClickSoundOption.fromId(2))
        assertEquals(ClickSoundOption.SOFT_TICK, ClickSoundOption.fromId(3))
        assertEquals(ClickSoundOption.CLASSIC, ClickSoundOption.fromId(-1))
        assertEquals(ClickSoundOption.CLASSIC, ClickSoundOption.fromId(99))
    }

    @Test
    fun testAppStateAndPreferencesDefaultToClassicAndLocalSubdivision() {
        val prefs = UserPreferencesSnapshot()
        assertEquals(ClickSoundOption.CLASSIC, prefs.clickSound)

        val defaultState = AppUiState()
        assertEquals(ClickSoundOption.CLASSIC, defaultState.clickSound)
        assertEquals(1, defaultState.localSubdivision)
        assertEquals(1, defaultState.subdivision)

        val inRoomState = AppUiState(
            localSubdivision = 3,
            activeRoom = RoomSnapshot(
                roomCode = "ABC123",
                participantCount = 2,
                isPlaying = true,
                bpm = 132.0,
                beatsPerBar = 6
            )
        )
        assertEquals(3, inRoomState.subdivision)
        assertEquals(132.0, inRoomState.bpm, 0.001)
        assertEquals(6, inRoomState.beatsPerBar)
    }

    @Test
    fun testTimeSignaturePresetsContainAllEightEntries() {
        val expected = listOf("4/4", "3/4", "6/8", "5/4", "7/8", "9/8", "12/8", "2/4")
        assertEquals(expected, TimeSignaturePreset.PRESETS.map { it.label })
        assertEquals(listOf(4, 3, 6, 5, 7, 9, 12, 2), TimeSignaturePreset.PRESETS.map { it.beatsPerBar })
    }
}
