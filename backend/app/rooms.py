"""In-memory room and transport schedule management."""

from dataclasses import dataclass, field
import json
import math
import secrets
from fastapi import WebSocket

from .clock import ClockFn, default_clock
from .models import (
    MAX_BAR_SNAP_NS,
    MAX_BEATS_PER_BAR,
    MAX_BPM,
    MIN_BEATS_PER_BAR,
    MIN_BPM,
    PLAY_LEAD_IN_NS,
    TRANSITION_FALLBACK_LEAD_NS,
    TRANSITION_MIN_LEAD_NS,
    VALID_SUBDIVISIONS,
    RoomState,
)

ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
ROOM_CODE_LENGTH = 6


@dataclass
class Room:
    """A rehearsal room with authoritative transport state and connected clients."""

    state: RoomState
    clients: set[WebSocket] = field(default_factory=set)


class RoomManager:
    """Manages active rooms, memberships, and scheduled transport transitions."""

    def __init__(self, clock_fn: ClockFn = default_clock) -> None:
        self.clock_fn = clock_fn
        self.rooms: dict[str, Room] = {}
        self._client_rooms: dict[WebSocket, str] = {}

    def generate_room_code(self) -> str:
        """Generate a unique 6-character uppercase alphanumeric room code."""
        for _ in range(1000):
            code = "".join(secrets.choice(ROOM_CODE_ALPHABET) for _ in range(ROOM_CODE_LENGTH))
            if code not in self.rooms:
                return code
        raise RuntimeError("Unable to allocate unique room code")

    def get_client_room(self, ws: WebSocket) -> Room | None:
        """Return the room currently joined by ``ws``, or ``None``."""
        code = self._client_rooms.get(ws)
        if code is None:
            return None
        return self.rooms.get(code)

    def create_room(self, ws: WebSocket) -> tuple[Room, Room | None]:
        """Create a new room and move ``ws`` into it.

        Returns ``(new_room, previous_room_if_still_occupied)``.
        """
        previous_room = self.leave_room(ws)
        code = self.generate_room_code()
        room = Room(state=RoomState(room_code=code), clients={ws})
        self.rooms[code] = room
        self._client_rooms[ws] = code
        return room, previous_room

    def join_room(self, ws: WebSocket, code: str) -> tuple[Room | None, Room | None]:
        """Join an existing room by code.

        Returns ``(joined_room, previous_room_if_still_occupied)``.
        If ``code`` does not exist, returns ``(None, None)`` without altering prior membership.
        """
        normalized = code.strip().upper()
        target = self.rooms.get(normalized)
        if target is None:
            return None, None

        previous_code = self._client_rooms.get(ws)
        if previous_code == normalized:
            return target, None

        previous_room = self.leave_room(ws)
        target.clients.add(ws)
        self._client_rooms[ws] = normalized
        return target, previous_room

    def leave_room(self, ws: WebSocket) -> Room | None:
        """Remove ``ws`` from its current room.

        Deletes empty rooms automatically. Returns the room if remaining peers exist,
        otherwise ``None``.
        """
        code = self._client_rooms.pop(ws, None)
        if code is None:
            return None
        room = self.rooms.get(code)
        if room is None:
            return None
        room.clients.discard(ws)
        if not room.clients:
            self.rooms.pop(code, None)
            return None
        return room

    def play(self, room: Room) -> RoomState:
        """Schedule playback 1.5 seconds in the future using the authoritative clock."""
        if not room.state.is_playing:
            room.state.is_playing = True
            room.state.start_server_time_ns = self.clock_fn() + PLAY_LEAD_IN_NS
            room.state.state_version += 1
        return room.state

    def stop(self, room: Room) -> RoomState:
        """Stop playback immediately and clear the scheduled start timestamp."""
        room.state.is_playing = False
        room.state.start_server_time_ns = 0
        room.state.state_version += 1
        return room.state

    def update_transport(
        self,
        room: Room,
        bpm: float | None = None,
        beats_per_bar: int | None = None,
        subdivision: int | None = None,
    ) -> RoomState:
        """Validate and apply BPM / meter / subdivision changes.

        When playing, anchors the transition cleanly to a future bar boundary (or +1s lead-in
        when bars are very long or playback has not started yet) so all peers stay locked.
        """
        new_bpm = room.state.bpm
        if bpm is not None:
            if isinstance(bpm, bool) or not isinstance(bpm, (int, float)):
                raise ValueError("bpm must be a number")
            bpm_float = float(bpm)
            if not math.isfinite(bpm_float) or not (MIN_BPM <= bpm_float <= MAX_BPM):
                raise ValueError(f"bpm must be between {MIN_BPM} and {MAX_BPM}")
            new_bpm = round(bpm_float, 2)

        new_bpb = room.state.beats_per_bar
        if beats_per_bar is not None:
            if isinstance(beats_per_bar, bool) or not isinstance(beats_per_bar, int):
                raise ValueError("beats_per_bar must be an integer")
            if not (MIN_BEATS_PER_BAR <= beats_per_bar <= MAX_BEATS_PER_BAR):
                raise ValueError(
                    f"beats_per_bar must be between {MIN_BEATS_PER_BAR} and {MAX_BEATS_PER_BAR}"
                )
            new_bpb = beats_per_bar

        new_sub = room.state.subdivision
        if subdivision is not None:
            if isinstance(subdivision, bool) or not isinstance(subdivision, int):
                raise ValueError("subdivision must be an integer")
            if subdivision not in VALID_SUBDIVISIONS:
                raise ValueError(f"subdivision must be one of {VALID_SUBDIVISIONS}")
            new_sub = subdivision

        if (
            new_bpm == room.state.bpm
            and new_bpb == room.state.beats_per_bar
            and new_sub == room.state.subdivision
        ):
            return room.state

        if room.state.is_playing:
            now_ns = self.clock_fn()
            old_bar_ns = int(round((60_000_000_000.0 / room.state.bpm) * room.state.beats_per_bar))
            if now_ns < room.state.start_server_time_ns:
                transition_ns = max(
                    room.state.start_server_time_ns,
                    now_ns + TRANSITION_FALLBACK_LEAD_NS,
                )
            elif old_bar_ns <= MAX_BAR_SNAP_NS:
                min_target_ns = now_ns + TRANSITION_MIN_LEAD_NS
                elapsed_to_target = min_target_ns - room.state.start_server_time_ns
                bars_needed = (elapsed_to_target + old_bar_ns - 1) // old_bar_ns
                transition_ns = room.state.start_server_time_ns + bars_needed * old_bar_ns
            else:
                transition_ns = now_ns + TRANSITION_FALLBACK_LEAD_NS
            room.state.start_server_time_ns = transition_ns

        room.state.bpm = new_bpm
        room.state.beats_per_bar = new_bpb
        room.state.subdivision = new_sub
        room.state.state_version += 1
        return room.state

    async def broadcast_state(self, room: Room) -> None:
        """Broadcast the current room state to all connected peers, pruning dead sockets."""
        clients_snapshot = list(room.clients)
        if not clients_snapshot:
            return
        payload = json.dumps(room.state.to_payload(len(clients_snapshot)))
        dead_clients: list[WebSocket] = []
        for client in clients_snapshot:
            try:
                await client.send_text(payload)
            except Exception:
                dead_clients.append(client)

        if dead_clients:
            for dead in dead_clients:
                self.leave_room(dead)
            if room.clients:
                updated_payload = json.dumps(room.state.to_payload(len(room.clients)))
                for client in list(room.clients):
                    try:
                        await client.send_text(updated_payload)
                    except Exception:
                        self.leave_room(client)
