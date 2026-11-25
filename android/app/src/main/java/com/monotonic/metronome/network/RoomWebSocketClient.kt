package com.monotonic.metronome.network

import com.monotonic.metronome.model.ConnectionStatus
import com.monotonic.metronome.model.CreateRoomMessage
import com.monotonic.metronome.model.JoinRoomMessage
import com.monotonic.metronome.model.LeaveRoomMessage
import com.monotonic.metronome.model.PlayMessage
import com.monotonic.metronome.model.RoomStateEvent
import com.monotonic.metronome.model.ServerErrorEvent
import com.monotonic.metronome.model.StopMessage
import com.monotonic.metronome.model.SyncPingMessage
import com.monotonic.metronome.model.SyncPongEvent
import com.monotonic.metronome.model.UpdateTransportMessage
import com.monotonic.metronome.sync.MonotonicClock
import com.monotonic.metronome.sync.SystemNanoTimeClock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URI
import java.util.concurrent.TimeUnit

class RoomWebSocketClient(
    private val clock: MonotonicClock = SystemNanoTimeClock,
    private val onSyncPong: (seq: Int, t1: Long, t2: Long, t3: Long, t4: Long) -> Unit,
    private val onRoomJoined: (RoomStateEvent) -> Unit,
    private val onRoomState: (RoomStateEvent) -> Unit,
    private val onServerError: (ServerErrorEvent) -> Unit,
    private val onConnectionStatusChanged: (ConnectionStatus, String?) -> Unit
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val socketLock = Any()
    private var activeSocket: WebSocket? = null
    private var activeUrl: String? = null
    private var isOpen = false
    private var pendingOnOpen: (() -> Unit)? = null

    fun connectAndExecute(rawUrl: String, action: () -> Unit) {
        val normalized = normalizeWebSocketUrl(rawUrl)
        synchronized(socketLock) {
            if (activeSocket != null && activeUrl == normalized && isOpen) {
                action()
                return
            }
            closeCurrentSocketLocked()
            activeUrl = normalized
            isOpen = false
            pendingOnOpen = action
            onConnectionStatusChanged(ConnectionStatus.CONNECTING, null)

            val request = try {
                Request.Builder().url(normalized).build()
            } catch (e: IllegalArgumentException) {
                pendingOnOpen = null
                onConnectionStatusChanged(
                    ConnectionStatus.DISCONNECTED,
                    e.message ?: "Invalid server URL"
                )
                return
            }

            lateinit var createdSocket: WebSocket
            createdSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val openAction: (() -> Unit)?
                        synchronized(socketLock) {
                            if (activeSocket !== webSocket) return
                            isOpen = true
                            openAction = pendingOnOpen
                            pendingOnOpen = null
                        }
                        onConnectionStatusChanged(ConnectionStatus.CONNECTED, null)
                        openAction?.invoke()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // Capture monotonic arrival timestamp immediately before any JSON parsing
                        val t4 = clock.nanoTime()
                        handleIncomingMessage(text, t4)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        val shouldNotify: Boolean
                        synchronized(socketLock) {
                            shouldNotify = (activeSocket === webSocket)
                            if (shouldNotify) {
                                activeSocket = null
                                isOpen = false
                                pendingOnOpen = null
                            }
                        }
                        if (shouldNotify) {
                            onConnectionStatusChanged(ConnectionStatus.DISCONNECTED, null)
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        val shouldNotify: Boolean
                        synchronized(socketLock) {
                            shouldNotify = (activeSocket === webSocket)
                            if (shouldNotify) {
                                activeSocket = null
                                isOpen = false
                                pendingOnOpen = null
                            }
                        }
                        if (shouldNotify) {
                            onConnectionStatusChanged(
                                ConnectionStatus.DISCONNECTED,
                                t.message ?: "WebSocket connection failed"
                            )
                        }
                    }
                }
            )
            activeSocket = createdSocket
        }
    }

    fun disconnect() {
        synchronized(socketLock) {
            closeCurrentSocketLocked()
        }
        onConnectionStatusChanged(ConnectionStatus.DISCONNECTED, null)
    }

    fun shutdown() {
        disconnect()
        client.dispatcher.executorService.shutdown()
    }

    fun sendSyncPing(seq: Int, t1: Long): Boolean {
        return sendRaw(json.encodeToString(SyncPingMessage(seq = seq, t1 = t1)))
    }

    fun createRoom(): Boolean = sendRaw(json.encodeToString(CreateRoomMessage()))

    fun joinRoom(roomCode: String): Boolean {
        return sendRaw(json.encodeToString(JoinRoomMessage(roomCode = roomCode.trim().uppercase())))
    }

    fun leaveRoom(): Boolean = sendRaw(json.encodeToString(LeaveRoomMessage()))

    fun play(): Boolean = sendRaw(json.encodeToString(PlayMessage()))

    fun stop(): Boolean = sendRaw(json.encodeToString(StopMessage()))

    fun updateTransport(
        bpm: Double? = null,
        beatsPerBar: Int? = null
    ): Boolean {
        return sendRaw(
            json.encodeToString(
                UpdateTransportMessage(
                    bpm = bpm,
                    beatsPerBar = beatsPerBar
                )
            )
        )
    }

    private fun sendRaw(payload: String): Boolean {
        val socket = synchronized(socketLock) {
            if (isOpen) activeSocket else null
        } ?: return false
        return socket.send(payload)
    }

    private fun closeCurrentSocketLocked() {
        pendingOnOpen = null
        isOpen = false
        val old = activeSocket
        activeSocket = null
        old?.close(1000, "Client closing")
    }

    private fun handleIncomingMessage(text: String, t4: Long) {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            when (root["type"]?.jsonPrimitive?.content) {
                "sync_pong" -> {
                    val pong = json.decodeFromString<SyncPongEvent>(text)
                    onSyncPong(pong.seq, pong.t1, pong.t2, pong.t3, t4)
                }

                "room_joined" -> {
                    val state = json.decodeFromString<RoomStateEvent>(text)
                    onRoomJoined(state)
                }

                "room_state" -> {
                    val state = json.decodeFromString<RoomStateEvent>(text)
                    onRoomState(state)
                }

                "error" -> {
                    val err = json.decodeFromString<ServerErrorEvent>(text)
                    onServerError(err)
                }
            }
        } catch (_: Exception) {
            // Ignore malformed frames cleanly
        }
    }

    companion object {
        fun normalizeWebSocketUrl(raw: String): String {
            var trimmed = raw.trim()
            if (trimmed.startsWith("http://", ignoreCase = true)) {
                trimmed = "ws://" + trimmed.substring(7)
            } else if (trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed = "wss://" + trimmed.substring(8)
            } else if (!trimmed.startsWith("ws://", ignoreCase = true) &&
                !trimmed.startsWith("wss://", ignoreCase = true)
            ) {
                trimmed = "ws://$trimmed"
            }

            return try {
                val uri = URI(trimmed)
                if (uri.path.isNullOrEmpty() || uri.path == "/") {
                    val base = trimmed.trimEnd('/')
                    "$base/ws"
                } else {
                    trimmed
                }
            } catch (_: Exception) {
                trimmed
            }
        }
    }
}
