"""Transport state models and validation constants."""

from dataclasses import dataclass
from typing import Any

MIN_BPM: float = 20.0
MAX_BPM: float = 500.0
MIN_BEATS_PER_BAR: int = 1
MAX_BEATS_PER_BAR: int = 16
VALID_SUBDIVISIONS: tuple[int, ...] = (1, 2, 3, 4)

PLAY_LEAD_IN_NS: int = 1_500_000_000
TRANSITION_MIN_LEAD_NS: int = 500_000_000
TRANSITION_FALLBACK_LEAD_NS: int = 1_000_000_000
MAX_BAR_SNAP_NS: int = 4_000_000_000


@dataclass
class RoomState:
    """Authoritative metronome playback schedule for a virtual rehearsal room."""

    room_code: str
    is_playing: bool = False
    bpm: float = 120.0
    beats_per_bar: int = 4
    subdivision: int = 1
    start_server_time_ns: int = 0
    state_version: int = 1

    def to_payload(self, participant_count: int, msg_type: str = "room_state") -> dict[str, Any]:
        """Serialize room state into a WebSocket protocol message."""
        return {
            "type": msg_type,
            "room_code": self.room_code,
            "participant_count": participant_count,
            "is_playing": self.is_playing,
            "bpm": self.bpm,
            "beats_per_bar": self.beats_per_bar,
            "subdivision": self.subdivision,
            "start_server_time_ns": self.start_server_time_ns,
            "state_version": self.state_version,
        }
