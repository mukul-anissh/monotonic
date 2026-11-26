"""mDNS / Zeroconf local network advertisement helpers for the Monotonic sync server."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
import ipaddress
import logging
import os
import re
import socket
import sys
from typing import Any

from zeroconf import IPVersion, ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

MDNS_SERVICE_TYPE = "_syncmetro._tcp.local."
DEFAULT_PORT = 8000
DEFAULT_WS_PATH = "/ws"
MDNS_PROTOCOL_VERSION = "1"

_UDP_PROBE_TARGETS: tuple[tuple[str, int], ...] = (
    ("8.8.8.8", 80),
    ("10.255.255.255", 1),
    ("192.168.255.255", 1),
    ("172.31.255.255", 1),
)

_TRUTHY_FLAGS = frozenset({"1", "true", "yes", "on"})

logger = logging.getLogger("uvicorn.error")


def should_disable_mdns(
    env: Mapping[str, str] | None = None,
    modules: Mapping[str, Any] | None = None,
) -> bool:
    """Return True when mDNS advertisement should be skipped (env flag or pytest run)."""
    active_env = os.environ if env is None else env
    active_modules = sys.modules if modules is None else modules

    if active_env.get("DISABLE_MDNS", "").strip().lower() in _TRUTHY_FLAGS:
        return True
    if "PYTEST_CURRENT_TEST" in active_env:
        return True
    if "pytest" in active_modules:
        return True
    return False


def _parse_valid_port(raw: str | None) -> int | None:
    if raw is None:
        return None
    stripped = raw.strip()
    if not stripped or not stripped.isdigit():
        return None
    port = int(stripped)
    if 1 <= port <= 65535:
        return port
    return None


def detect_server_port(
    argv: Sequence[str] | None = None,
    env: Mapping[str, str] | None = None,
    default_port: int = DEFAULT_PORT,
) -> int:
    """Detect active server port from CLI args, then PORT env var, else default_port."""
    active_argv = sys.argv[1:] if argv is None else argv
    active_env = os.environ if env is None else env

    cli_port: int | None = None
    i = 0
    while i < len(active_argv):
        arg = active_argv[i]
        candidate: str | None = None
        step = 1
        if arg in ("--port", "-p"):
            if i + 1 < len(active_argv):
                candidate = active_argv[i + 1]
                step = 2
        elif arg.startswith("--port="):
            candidate = arg.split("=", 1)[1]
        elif arg.startswith("-p="):
            candidate = arg.split("=", 1)[1]
        elif arg.startswith("-p") and len(arg) > 2 and arg[2:].isdigit():
            candidate = arg[2:]

        parsed = _parse_valid_port(candidate)
        if parsed is not None:
            cli_port = parsed
        i += step

    if cli_port is not None:
        return cli_port

    env_port = _parse_valid_port(active_env.get("PORT"))
    if env_port is not None:
        return env_port

    return default_port


def _is_usable_lan_ipv4(ip: str) -> bool:
    """Return True if ``ip`` is a usable non-loopback, non-link-local IPv4 address."""
    try:
        addr = ipaddress.IPv4Address(ip)
    except ipaddress.AddressValueError:
        return False
    return not (addr.is_loopback or addr.is_unspecified or addr.is_link_local or addr.is_multicast)


def detect_lan_ipv4(
    env: Mapping[str, str] | None = None,
    socket_factory: Callable[..., Any] = socket.socket,
) -> str | None:
    """Detect the primary LAN IPv4 address without transmitting packets on the wire."""
    active_env = os.environ if env is None else env

    override = active_env.get("ADVERTISE_HOST", "").strip()
    if override:
        try:
            addr = ipaddress.IPv4Address(override)
            return str(addr)
        except ipaddress.AddressValueError:
            try:
                resolved = socket.gethostbyname(override)
                addr = ipaddress.IPv4Address(resolved)
                return str(addr)
            except (OSError, ipaddress.AddressValueError):
                logger.warning("Ignoring invalid ADVERTISE_HOST override: %r", override)

    for target in _UDP_PROBE_TARGETS:
        sock = None
        try:
            sock = socket_factory(socket.AF_INET, socket.SOCK_DGRAM)
            sock.connect(target)
            local_ip = sock.getsockname()[0]
            if isinstance(local_ip, str) and _is_usable_lan_ipv4(local_ip):
                return local_ip
        except OSError:
            continue
        finally:
            if sock is not None:
                try:
                    sock.close()
                except OSError:
                    pass

    try:
        for entry in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            sockaddr = entry[4]
            if sockaddr:
                candidate = sockaddr[0]
                if isinstance(candidate, str) and _is_usable_lan_ipv4(candidate):
                    return candidate
    except OSError:
        pass

    return None


def get_friendly_hostname(raw_hostname: str | None = None) -> str:
    """Return a clean human-friendly machine name suitable for mDNS service naming."""
    raw = socket.gethostname() if raw_hostname is None else raw_hostname
    cleaned = re.sub(r"[\x00-\x1f\x7f]+", "", raw).strip()
    if "." in cleaned:
        cleaned = cleaned.split(".", 1)[0].strip()
    if not cleaned:
        return "Monotonic-Server"
    encoded = cleaned.encode("utf-8")[:63]
    truncated = encoded.decode("utf-8", errors="ignore").strip()
    return truncated or "Monotonic-Server"


def _dns_safe_host_label(name: str) -> str:
    """Sanitize a friendly name into an RFC-compliant DNS host label for ``<label>.local.``."""
    sanitized = re.sub(r"[^A-Za-z0-9-]+", "-", name).strip("-").lower()
    if not sanitized:
        return "monotonic-server"
    return sanitized[:63].strip("-") or "monotonic-server"


@dataclass(frozen=True)
class MdnsConfig:
    """Resolved mDNS service advertisement configuration."""

    host_ip: str
    port: int
    friendly_name: str
    service_type: str = MDNS_SERVICE_TYPE
    path: str = DEFAULT_WS_PATH
    version: str = MDNS_PROTOCOL_VERSION

    @property
    def service_name(self) -> str:
        return f"{self.friendly_name}.{self.service_type}"

    @property
    def server_fqdn(self) -> str:
        return f"{_dns_safe_host_label(self.friendly_name)}.local."

    @property
    def txt_properties(self) -> dict[str, str]:
        return {
            "path": self.path,
            "name": self.friendly_name,
            "version": self.version,
        }

    @property
    def ws_url(self) -> str:
        return f"ws://{self.host_ip}:{self.port}{self.path}"


def build_mdns_config(
    argv: Sequence[str] | None = None,
    env: Mapping[str, str] | None = None,
    hostname: str | None = None,
    socket_factory: Callable[..., Any] = socket.socket,
) -> MdnsConfig | None:
    """Build complete mDNS service settings or return None if no LAN IPv4 is available."""
    host_ip = detect_lan_ipv4(env=env, socket_factory=socket_factory)
    if host_ip is None:
        return None
    port = detect_server_port(argv=argv, env=env)
    friendly_name = get_friendly_hostname(hostname)
    return MdnsConfig(host_ip=host_ip, port=port, friendly_name=friendly_name)


class MdnsAdvertiser:
    """Manages async mDNS service registration and clean unregistration."""

    def __init__(
        self,
        zeroconf_factory: Callable[[], Any] | None = None,
        config_builder: Callable[[], MdnsConfig | None] = build_mdns_config,
        disable_check: Callable[[], bool] = should_disable_mdns,
    ) -> None:
        self._zeroconf_factory = zeroconf_factory or (
            lambda: AsyncZeroconf(ip_version=IPVersion.V4Only)
        )
        self._config_builder = config_builder
        self._disable_check = disable_check
        self._aiozc: Any | None = None
        self._info: ServiceInfo | None = None
        self._config: MdnsConfig | None = None

    async def start(self) -> MdnsConfig | None:
        """Start mDNS advertisement if enabled and a LAN interface is available."""
        if self._disable_check():
            return None

        config = self._config_builder()
        if config is None:
            logger.warning("mDNS advertisement skipped: no LAN IPv4 address detected.")
            return None

        info = ServiceInfo(
            type_=config.service_type,
            name=config.service_name,
            addresses=[socket.inet_aton(config.host_ip)],
            port=config.port,
            properties=config.txt_properties,
            server=config.server_fqdn,
        )

        aiozc: Any | None = None
        try:
            aiozc = self._zeroconf_factory()
            await aiozc.async_register_service(info)
        except Exception as exc:
            if aiozc is not None:
                try:
                    await aiozc.async_close()
                except Exception:
                    pass
            logger.warning("mDNS advertisement disabled due to network/OS error: %s", exc)
            return None

        self._aiozc = aiozc
        self._info = info
        self._config = config
        logger.info(
            "mDNS service registered: %s | LAN WebSocket URL: %s",
            config.service_name,
            config.ws_url,
        )
        return config

    async def stop(self) -> None:
        """Unregister the active mDNS service and close the AsyncZeroconf instance."""
        aiozc = self._aiozc
        info = self._info
        self._aiozc = None
        self._info = None
        self._config = None

        if aiozc is None:
            return

        if info is not None:
            try:
                await aiozc.async_unregister_service(info)
            except Exception as exc:
                logger.warning("Error unregistering mDNS service: %s", exc)

        try:
            await aiozc.async_close()
        except Exception as exc:
            logger.warning("Error closing AsyncZeroconf: %s", exc)
