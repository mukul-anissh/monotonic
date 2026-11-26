"""FastAPI WebSocket server for Monotonic clock sync and transport state."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
import json
from typing import Any
from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from .clock import ClockFn, default_clock
from .mdns import MdnsAdvertiser
from .rooms import RoomManager


async def _send_error(websocket: WebSocket, code: str, message: str) -> None:
    await websocket.send_text(json.dumps({"type": "error", "code": code, "message": message}))


def _is_strict_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def create_app(clock_fn: ClockFn = default_clock) -> FastAPI:
    """Create the FastAPI application wired with an injectable monotonic clock."""
    advertiser = MdnsAdvertiser()

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        application.state.mdns_config = await advertiser.start()
        try:
            yield
        finally:
            await advertiser.stop()
            application.state.mdns_config = None

    application = FastAPI(title="Monotonic Sync Server", version="0.1.0", lifespan=lifespan)
    manager = RoomManager(clock_fn=clock_fn)
    application.state.room_manager = manager

    @application.get("/health")
    async def health() -> dict[str, Any]:
        return {"status": "ok", "rooms": len(manager.rooms)}

    @application.websocket("/ws")
    async def websocket_endpoint(websocket: WebSocket) -> None:
        await websocket.accept()
        try:
            while True:
                raw = await websocket.receive_text()
                recv_ns = clock_fn()

                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    await _send_error(websocket, "invalid_json", "Message must be valid JSON")
                    continue

                if not isinstance(data, dict):
                    await _send_error(websocket, "invalid_payload", "Message must be a JSON object")
                    continue

                msg_type = data.get("type")

                if msg_type == "sync_ping":
                    seq = data.get("seq")
                    t1 = data.get("t1")
                    if not _is_strict_int(seq) or not _is_strict_int(t1):
                        await _send_error(
                            websocket,
                            "invalid_sync_ping",
                            "sync_ping requires integer seq and t1",
                        )
                        continue
                    t2 = recv_ns
                    t3 = clock_fn()
                    await websocket.send_text(
                        json.dumps(
                            {
                                "type": "sync_pong",
                                "seq": seq,
                                "t1": t1,
                                "t2": t2,
                                "t3": t3,
                            }
                        )
                    )
                    continue

                if msg_type == "create_room":
                    room, previous_room = manager.create_room(websocket)
                    if previous_room is not None:
                        await manager.broadcast_state(previous_room)
                    await websocket.send_text(
                        json.dumps(room.state.to_payload(len(room.clients), msg_type="room_joined"))
                    )
                    await manager.broadcast_state(room)
                    continue

                if msg_type == "join_room":
                    room_code = data.get("room_code")
                    if not isinstance(room_code, str) or not room_code.strip():
                        await _send_error(websocket, "invalid_room_code", "room_code is required")
                        continue
                    room, previous_room = manager.join_room(websocket, room_code)
                    if room is None:
                        await _send_error(
                            websocket,
                            "room_not_found",
                            f"Room '{room_code.strip().upper()}' does not exist",
                        )
                        continue
                    if previous_room is not None:
                        await manager.broadcast_state(previous_room)
                    await websocket.send_text(
                        json.dumps(room.state.to_payload(len(room.clients), msg_type="room_joined"))
                    )
                    await manager.broadcast_state(room)
                    continue

                if msg_type == "leave_room":
                    remaining_room = manager.leave_room(websocket)
                    if remaining_room is not None:
                        await manager.broadcast_state(remaining_room)
                    continue

                if msg_type == "play":
                    room = manager.get_client_room(websocket)
                    if room is None:
                        await _send_error(websocket, "not_in_room", "Join a room first")
                        continue
                    manager.play(room)
                    await manager.broadcast_state(room)
                    continue

                if msg_type == "stop":
                    room = manager.get_client_room(websocket)
                    if room is None:
                        await _send_error(websocket, "not_in_room", "Join a room first")
                        continue
                    manager.stop(room)
                    await manager.broadcast_state(room)
                    continue

                if msg_type == "update_transport":
                    room = manager.get_client_room(websocket)
                    if room is None:
                        await _send_error(websocket, "not_in_room", "Join a room first")
                        continue
                    try:
                        manager.update_transport(
                            room,
                            bpm=data.get("bpm"),
                            beats_per_bar=data.get("beats_per_bar"),
                            subdivision=data.get("subdivision"),
                        )
                    except ValueError as exc:
                        await _send_error(websocket, "invalid_transport", str(exc))
                        continue
                    await manager.broadcast_state(room)
                    continue

                await _send_error(
                    websocket,
                    "unknown_message_type",
                    f"Unsupported message type: {msg_type}",
                )
        except WebSocketDisconnect:
            pass
        finally:
            remaining_room = manager.leave_room(websocket)
            if remaining_room is not None:
                await manager.broadcast_state(remaining_room)

    return application


app = create_app()
