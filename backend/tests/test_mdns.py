"""Tests for backend mDNS configuration helpers and FastAPI lifespan hooks."""

import asyncio
import logging
import socket
from typing import Any

from fastapi.testclient import TestClient

from app.main import create_app
from app.mdns import (
    DEFAULT_PORT,
    MDNS_SERVICE_TYPE,
    MdnsAdvertiser,
    MdnsConfig,
    build_mdns_config,
    detect_lan_ipv4,
    detect_server_port,
    get_friendly_hostname,
    should_disable_mdns,
)


class _FakeUdpSocket:
    def __init__(self, responses: dict[tuple[str, int], str | OSError]) -> None:
        self._responses = responses
        self._selected_ip: str | None = None
        self.closed = False

    def connect(self, target: tuple[str, int]) -> None:
        outcome = self._responses.get(target, OSError("unreachable"))
        if isinstance(outcome, OSError):
            raise outcome
        self._selected_ip = outcome

    def getsockname(self) -> tuple[str, int]:
        if self._selected_ip is None:
            raise OSError("not connected")
        return (self._selected_ip, 54321)

    def close(self) -> None:
        self.closed = True


class _FakeAsyncZeroconf:
    def __init__(self, fail_on_register: Exception | None = None) -> None:
        self.fail_on_register = fail_on_register
        self.registered: list[Any] = []
        self.unregistered: list[Any] = []
        self.closed = False

    async def async_register_service(self, info: Any) -> None:
        if self.fail_on_register is not None:
            raise self.fail_on_register
        self.registered.append(info)

    async def async_unregister_service(self, info: Any) -> None:
        self.unregistered.append(info)

    async def async_close(self) -> None:
        self.closed = True


def test_detect_server_port_cli_env_and_default() -> None:
    assert detect_server_port(argv=["--port", "6969"], env={"PORT": "9000"}) == 6969
    assert detect_server_port(argv=["--port=6969"], env={"PORT": "9000"}) == 6969
    assert detect_server_port(argv=["-p", "7070"], env={"PORT": "9000"}) == 7070
    assert detect_server_port(argv=["-p=7070"], env={"PORT": "9000"}) == 7070
    assert detect_server_port(argv=["-p7070"], env={"PORT": "9000"}) == 7070
    assert detect_server_port(argv=["--port", "8001", "--port", "8002"], env={}) == 8002
    assert detect_server_port(argv=["app.main:app", "--host", "0.0.0.0"], env={"PORT": "7777"}) == 7777
    assert detect_server_port(argv=["--port", "0"], env={"PORT": "70000"}) == DEFAULT_PORT
    assert detect_server_port(argv=["--port", "notanint"], env={"PORT": "abc"}) == DEFAULT_PORT
    assert detect_server_port(argv=[], env={}) == DEFAULT_PORT


def test_detect_lan_ipv4_probes_and_override(monkeypatch: Any) -> None:
    assert detect_lan_ipv4(env={"ADVERTISE_HOST": "10.20.30.40"}) == "10.20.30.40"

    def fake_factory(_family: int, _type: int) -> _FakeUdpSocket:
        return _FakeUdpSocket(
            {
                ("8.8.8.8", 80): OSError("no internet"),
                ("10.255.255.255", 1): "10.145.118.121",
            }
        )

    assert detect_lan_ipv4(env={}, socket_factory=fake_factory) == "10.145.118.121"

    def loopback_and_link_local_factory(_family: int, _type: int) -> _FakeUdpSocket:
        return _FakeUdpSocket(
            {
                ("8.8.8.8", 80): "127.0.0.1",
                ("10.255.255.255", 1): "169.254.12.34",
                ("192.168.255.255", 1): "0.0.0.0",
                ("172.31.255.255", 1): "192.168.43.1",
            }
        )

    assert detect_lan_ipv4(env={}, socket_factory=loopback_and_link_local_factory) == "192.168.43.1"

    def offline_factory(_family: int, _type: int) -> _FakeUdpSocket:
        return _FakeUdpSocket({})

    monkeypatch.setattr(socket, "getaddrinfo", lambda *args, **kwargs: [])
    assert detect_lan_ipv4(env={}, socket_factory=offline_factory) is None


def test_get_friendly_hostname_and_build_mdns_config() -> None:
    assert get_friendly_hostname("studio-laptop.local") == "studio-laptop"
    assert get_friendly_hostname("Rehearsal_MacBook.lan") == "Rehearsal_MacBook"
    assert get_friendly_hostname("   ") == "Monotonic-Server"

    config = build_mdns_config(
        argv=["--port", "6969"],
        env={"ADVERTISE_HOST": "192.168.1.42"},
        hostname="Rehearsal Mac.local",
    )
    assert config is not None
    assert config.service_type == MDNS_SERVICE_TYPE == "_syncmetro._tcp.local."
    assert config.service_name == "Rehearsal Mac._syncmetro._tcp.local."
    assert config.server_fqdn == "rehearsal-mac.local."
    assert config.txt_properties == {
        "path": "/ws",
        "name": "Rehearsal Mac",
        "version": "1",
    }
    assert config.ws_url == "ws://192.168.1.42:6969/ws"


def test_should_disable_mdns_flags() -> None:
    assert should_disable_mdns(env={"DISABLE_MDNS": "1"}, modules={}) is True
    assert should_disable_mdns(env={"DISABLE_MDNS": "true"}, modules={}) is True
    assert should_disable_mdns(env={"PYTEST_CURRENT_TEST": "foo"}, modules={}) is True
    assert should_disable_mdns(env={}, modules={"pytest": object()}) is True
    assert should_disable_mdns(env={"DISABLE_MDNS": "0"}, modules={}) is False


def test_mdns_advertiser_lifecycle_and_failure_tolerance(caplog: Any) -> None:
    fake_zc = _FakeAsyncZeroconf()
    sample_config = MdnsConfig(host_ip="192.168.1.50", port=6969, friendly_name="StudioBox")
    advertiser = MdnsAdvertiser(
        zeroconf_factory=lambda: fake_zc,
        config_builder=lambda: sample_config,
        disable_check=lambda: False,
    )

    with caplog.at_level(logging.INFO, logger="uvicorn.error"):
        started = asyncio.run(advertiser.start())
        assert started == sample_config
        assert len(fake_zc.registered) == 1
        info = fake_zc.registered[0]
        assert info.name == "StudioBox._syncmetro._tcp.local."
        assert info.type == "_syncmetro._tcp.local."
        assert info.port == 6969
        assert info.properties == {b"path": b"/ws", b"name": b"StudioBox", b"version": b"1"}
        assert "ws://192.168.1.50:6969/ws" in caplog.text

        asyncio.run(advertiser.stop())
        assert len(fake_zc.unregistered) == 1
        assert fake_zc.closed is True

    failing_zc = _FakeAsyncZeroconf(fail_on_register=OSError("multicast blocked"))
    failing_advertiser = MdnsAdvertiser(
        zeroconf_factory=lambda: failing_zc,
        config_builder=lambda: sample_config,
        disable_check=lambda: False,
    )
    with caplog.at_level(logging.WARNING, logger="uvicorn.error"):
        result = asyncio.run(failing_advertiser.start())
        assert result is None
        assert failing_zc.closed is True
        assert "multicast blocked" in caplog.text


def test_fastapi_lifespan_skips_live_mdns_inside_pytest() -> None:
    app = create_app()
    with TestClient(app) as client:
        resp = client.get("/health")
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok", "rooms": 0}
        assert app.state.mdns_config is None
