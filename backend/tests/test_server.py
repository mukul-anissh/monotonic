"""Tests for Monotonic authoritative sync server."""

from pathlib import Path
import re
from fastapi.testclient import TestClient

from app.main import create_app


class FakeMonotonicClock:
    """Deterministic monotonic clock for testing SNTP and schedule math."""

    def __init__(self, initial_ns: int = 10_000_000_000_000) -> None:
        self.now_ns = initial_ns
        self.auto_step_ns = 0

    def __call__(self) -> int:
        val = self.now_ns
        self.now_ns += self.auto_step_ns
        return val

    def advance(self, delta_ns: int) -> None:
        self.now_ns += delta_ns


def test_health_endpoint() -> None:
    clock = FakeMonotonicClock()
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok", "rooms": 0}

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "create_room"})
        ws.receive_json()  # room_joined
        ws.receive_json()  # room_state
        resp2 = client.get("/health")
        assert resp2.status_code == 200
        assert resp2.json() == {"status": "ok", "rooms": 1}

    resp3 = client.get("/health")
    assert resp3.json() == {"status": "ok", "rooms": 0}


def test_create_room_and_code_format() -> None:
    clock = FakeMonotonicClock()
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "create_room"})
        joined = ws.receive_json()
        state = ws.receive_json()

        assert joined["type"] == "room_joined"
        assert state["type"] == "room_state"
        assert re.fullmatch(r"[A-Z0-9]{6}", joined["room_code"])
        assert joined["room_code"] == state["room_code"]
        assert state["participant_count"] == 1
        assert state["is_playing"] is False
        assert state["bpm"] == 120.0
        assert state["beats_per_bar"] == 4
        assert state["subdivision"] == 1
        assert state["start_server_time_ns"] == 0
        assert state["state_version"] == 1


def test_join_unknown_room_returns_error() -> None:
    clock = FakeMonotonicClock()
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "join_room", "room_code": "ZZZZZZ"})
        err = ws.receive_json()
        assert err["type"] == "error"
        assert err["code"] == "room_not_found"

        # Connection stays open and usable
        ws.send_json({"type": "create_room"})
        joined = ws.receive_json()
        assert joined["type"] == "room_joined"


def test_multi_client_join_broadcast_and_leave_cleanup() -> None:
    clock = FakeMonotonicClock()
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws1:
        ws1.send_json({"type": "create_room"})
        joined1 = ws1.receive_json()
        state1 = ws1.receive_json()
        code = joined1["room_code"]
        assert state1["participant_count"] == 1

        with client.websocket_connect("/ws") as ws2:
            ws2.send_json({"type": "join_room", "room_code": code.lower()})
            joined2 = ws2.receive_json()
            state2 = ws2.receive_json()
            state1_on_join = ws1.receive_json()

            assert joined2["type"] == "room_joined"
            assert joined2["room_code"] == code
            assert state2["participant_count"] == 2
            assert state1_on_join["participant_count"] == 2

        # ws2 disconnected cleanly -> ws1 notified of participant_count == 1
        state1_on_leave = ws1.receive_json()
        assert state1_on_leave["participant_count"] == 1
        assert code in app.state.room_manager.rooms

    # Both left -> room cleaned up
    assert code not in app.state.room_manager.rooms


def test_sntp_sync_ping_pong_timestamps() -> None:
    # Server clock is +500_000_000_000 ns (500s) ahead of client clock
    true_offset_ns = 500_000_000_000
    clock = FakeMonotonicClock(initial_ns=1_000_000_000_000 + true_offset_ns)
    clock.auto_step_ns = 50_000  # 50 us server processing between t2 and t3

    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        t1_client = 1_000_000_000_000 - 2_000_000  # 2ms uplink transit
        ws.send_json({"type": "sync_ping", "seq": 7, "t1": t1_client})
        pong = ws.receive_json()

        assert pong["type"] == "sync_pong"
        assert pong["seq"] == 7
        assert pong["t1"] == t1_client
        t2 = pong["t2"]
        t3 = pong["t3"]
        assert t2 == 1_000_000_000_000 + true_offset_ns
        assert t3 == t2 + 50_000

        # Simulate symmetric 2ms downlink transit
        t4_client = (t3 - true_offset_ns) + 2_000_000
        rtt_ns = (t4_client - t1_client) - (t3 - t2)
        offset_ns = ((t2 - t1_client) + (t3 - t4_client)) // 2

        assert rtt_ns == 4_000_000
        assert offset_ns == true_offset_ns


def test_play_schedules_1_5s_future_and_broadcasts() -> None:
    clock = FakeMonotonicClock(initial_ns=20_000_000_000_000)
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws1:
        ws1.send_json({"type": "create_room"})
        code = ws1.receive_json()["room_code"]
        ws1.receive_json()  # state

        with client.websocket_connect("/ws") as ws2:
            ws2.send_json({"type": "join_room", "room_code": code})
            ws2.receive_json()  # joined
            ws2.receive_json()  # state
            ws1.receive_json()  # state

            clock.advance(5_000_000_000)
            expected_start = clock.now_ns + 1_500_000_000
            ws2.send_json({"type": "play"})

            s1 = ws1.receive_json()
            s2 = ws2.receive_json()
            assert s1 == s2
            assert s1["is_playing"] is True
            assert s1["start_server_time_ns"] == expected_start
            assert s1["state_version"] == 2


def test_stop_clears_playback_and_broadcasts() -> None:
    clock = FakeMonotonicClock(initial_ns=20_000_000_000_000)
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "create_room"})
        ws.receive_json()
        ws.receive_json()

        ws.send_json({"type": "play"})
        playing = ws.receive_json()
        assert playing["is_playing"] is True
        assert playing["state_version"] == 2

        ws.send_json({"type": "stop"})
        stopped = ws.receive_json()
        assert stopped["is_playing"] is False
        assert stopped["start_server_time_ns"] == 0
        assert stopped["state_version"] == 3


def test_update_transport_while_stopped_and_bounds_validation() -> None:
    clock = FakeMonotonicClock()
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "create_room"})
        ws.receive_json()
        ws.receive_json()

        ws.send_json(
            {
                "type": "update_transport",
                "bpm": 135.5,
                "beats_per_bar": 7,
                "subdivision": 2,
            }
        )
        updated = ws.receive_json()
        assert updated["bpm"] == 135.5
        assert updated["beats_per_bar"] == 7
        assert updated["subdivision"] == 2
        assert updated["is_playing"] is False
        assert updated["start_server_time_ns"] == 0
        assert updated["state_version"] == 2

        invalid_payloads = [
            {"type": "update_transport", "bpm": 29.0},
            {"type": "update_transport", "bpm": 301.0},
            {"type": "update_transport", "bpm": True},
            {"type": "update_transport", "beats_per_bar": 1},
            {"type": "update_transport", "beats_per_bar": 13},
            {"type": "update_transport", "subdivision": 3},
        ]
        for payload in invalid_payloads:
            ws.send_json(payload)
            err = ws.receive_json()
            assert err["type"] == "error"
            assert err["code"] == "invalid_transport"


def test_update_transport_while_playing_snaps_to_next_bar_boundary() -> None:
    # Default 120 BPM, 4/4 -> 1 beat = 500ms, 1 bar = 2_000_000_000 ns (2.0s)
    clock = FakeMonotonicClock(initial_ns=50_000_000_000_000)
    app = create_app(clock_fn=clock)
    client = TestClient(app)

    with client.websocket_connect("/ws") as ws:
        ws.send_json({"type": "create_room"})
        ws.receive_json()
        ws.receive_json()

        ws.send_json({"type": "play"})
        play_state = ws.receive_json()
        start_ns = play_state["start_server_time_ns"]  # t0 + 1.5s

        # Case 1: 1.0s into bar 0 (1.0s left >= 500ms lead) -> snaps to start_ns + 2.0s (bar 1)
        clock.now_ns = start_ns + 1_000_000_000
        ws.send_json({"type": "update_transport", "bpm": 140.0})
        state1 = ws.receive_json()
        assert state1["bpm"] == 140.0
        assert state1["start_server_time_ns"] == start_ns + 2_000_000_000

        # Case 2: At 140 BPM 4/4, old_bar_ns = round((60e9 / 140) * 4)
        bar_ns_140 = int(round((60_000_000_000.0 / 140.0) * 4))
        anchor2 = state1["start_server_time_ns"]
        # Advance to within 200ms of end of bar 0 (< 500ms min lead) -> skips to bar 2
        clock.now_ns = anchor2 + bar_ns_140 - 200_000_000
        ws.send_json({"type": "update_transport", "beats_per_bar": 3})
        state2 = ws.receive_json()
        assert state2["beats_per_bar"] == 3
        assert state2["start_server_time_ns"] == anchor2 + 2 * bar_ns_140

        # Case 3: Very slow bar (> 4s): set 30 BPM, 12/4 -> bar = 24s, verify +1s fallback
        anchor3 = state2["start_server_time_ns"]
        clock.now_ns = anchor3 + 100_000_000
        ws.send_json({"type": "update_transport", "bpm": 30.0, "beats_per_bar": 12})
        ws.receive_json()  # transitioned cleanly

        # Now while bar is 24s (> MAX_BAR_SNAP_NS), updating should schedule now + 1s
        state3_start = app.state.room_manager.rooms[state2["room_code"]].state.start_server_time_ns
        clock.now_ns = state3_start + 500_000_000
        expected_fallback = clock.now_ns + 1_000_000_000
        ws.send_json({"type": "update_transport", "bpm": 90.0})
        state4 = ws.receive_json()
        assert state4["start_server_time_ns"] == expected_fallback


def test_no_wall_clock_usage() -> None:
    app_dir = Path(__file__).resolve().parent.parent / "app"
    for py_file in app_dir.glob("*.py"):
        source = py_file.read_text(encoding="utf-8")
        assert "time.time" not in source, f"Wall clock usage found in {py_file.name}"
