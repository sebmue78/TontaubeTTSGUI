"""Freigabe des eingebauten Tontaube-Servers im lokalen Netz (für die Android-App).

Die Einstellungen liegen als JSON im Datenordner der App. Für die Verbindung zeigt die
Oberfläche Adresse, Port und API-Key sowie einen QR-Code mit einem Verbindungslink an::

    tontaube://connect?url=http%3A%2F%2F192.168.1.20%3A8080&key=…&name=MacBook

Die Android-App liest genau dieses Format (siehe ``android/…/ConnectLink.kt``).
"""

from __future__ import annotations

import json
import platform
import secrets
import socket
import subprocess
from dataclasses import asdict, dataclass, field
from pathlib import Path
from urllib.parse import quote, urlencode

DEFAULT_LAN_PORT = 8080
CONNECT_SCHEME = "tontaube://connect"


def generate_key() -> str:
    """32 Zeichen, URL-sicher, gut abtippbar (keine Sonderzeichen außer - und _)."""
    return secrets.token_urlsafe(24)


@dataclass
class NetworkSettings:
    enabled: bool = False
    api_key: str = ""
    port: int = DEFAULT_LAN_PORT
    path: Path | None = field(default=None, repr=False, compare=False)

    @classmethod
    def load(cls, path: Path) -> "NetworkSettings":
        try:
            data = json.loads(Path(path).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            data = {}
        port = data.get("port")
        return cls(
            enabled=bool(data.get("enabled", False)),
            api_key=str(data.get("api_key") or ""),
            port=int(port) if isinstance(port, int) and 1 <= port <= 65535 else DEFAULT_LAN_PORT,
            path=Path(path),
        )

    def regenerate_key(self) -> str:
        self.api_key = generate_key()
        return self.api_key

    def save(self) -> None:
        if self.path is None:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        data = {k: v for k, v in asdict(self).items() if k != "path"}
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        try:
            tmp.chmod(0o600)  # enthält den API-Key
        except OSError:
            pass
        tmp.replace(self.path)


def _primary_ipv4() -> str | None:
    """IPv4 der Schnittstelle mit Standardroute (es wird kein Paket gesendet)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("192.0.2.1", 9))  # TEST-NET-1, nur für die Routenwahl
            ip = s.getsockname()[0]
        except OSError:
            return None
    return None if ip.startswith("127.") else ip


def _all_ipv4() -> list[str]:
    ips: list[str] = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    return ips


def _is_private(ip: str) -> bool:
    try:
        a, b = (int(x) for x in ip.split(".")[:2])
    except ValueError:
        return False
    return a == 10 or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168) or (a == 169 and b == 254)


def bonjour_name() -> str | None:
    """``Rechnername.local`` auf macOS (bleibt gleich, auch wenn sich die IP ändert)."""
    if platform.system() != "Darwin":
        return None
    try:
        name = subprocess.run(
            ["scutil", "--get", "LocalHostName"], capture_output=True, text=True, timeout=3
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return f"{name}.local" if name else None


def lan_addresses() -> list[str]:
    """Adressen, unter denen das Handy den Rechner erreicht; private IPv4 zuerst."""
    ips: list[str] = []
    primary = _primary_ipv4()
    if primary:
        ips.append(primary)
    ips += [ip for ip in _all_ipv4() if ip not in ips]
    ips.sort(key=lambda ip: (ip != primary, not _is_private(ip)))
    return ips


def device_name() -> str:
    if platform.system() == "Darwin":
        try:
            name = subprocess.run(
                ["scutil", "--get", "ComputerName"], capture_output=True, text=True, timeout=3
            ).stdout.strip()
            if name:
                return name
        except (OSError, subprocess.SubprocessError):
            pass
    return socket.gethostname().split(".")[0] or "Tontaube"


def connect_link(url: str, api_key: str, name: str = "") -> str:
    params = {"url": url, "key": api_key}
    if name:
        params["name"] = name
    return f"{CONNECT_SCHEME}?{urlencode(params, quote_via=quote)}"


def qr_svg_data_uri(text: str, scale: int = 6) -> str | None:
    """QR-Code als SVG-Data-URI (braucht das Paket ``segno``; ohne es ``None``)."""
    try:
        import segno
    except ImportError:
        return None
    return segno.make(text, error="m").svg_data_uri(scale=scale, border=2, dark="#111", light="#fff")
