package com.monotonic.metronome.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPingMessage(
    val type: String = "sync_ping",
    val seq: Int,
    val t1: Long
)

@Serializable
data class CreateRoomMessage(
    val type: String = "create_room"
)

@Serializable
data class JoinRoomMessage(
    val type: String = "join_room",
    @SerialName("room_code") val roomCode: String
)

@Serializable
data class LeaveRoomMessage(
    val type: String = "leave_room"
)

@Serializable
data class PlayMessage(
    val type: String = "play"
)

@Serializable
data class StopMessage(
    val type: String = "stop"
)

@Serializable
data class UpdateTransportMessage(
    val type: String = "update_transport",
    val bpm: Double? = null,
    @SerialName("beats_per_bar") val beatsPerBar: Int? = null,
    val subdivision: Int? = null
)

@Serializable
data class SyncPongEvent(
    val seq: Int,
    val t1: Long,
    val t2: Long,
    val t3: Long
)

@Serializable
data class RoomStateEvent(
    val type: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("participant_count") val participantCount: Int,
    @SerialName("is_playing") val isPlaying: Boolean,
    val bpm: Double,
    @SerialName("beats_per_bar") val beatsPerBar: Int,
    val subdivision: Int,
    @SerialName("start_server_time_ns") val startServerTimeNs: Long,
    @SerialName("state_version") val stateVersion: Long
)

@Serializable
data class ServerErrorEvent(
    val code: String = "error",
    val message: String = "Unknown server error"
)
