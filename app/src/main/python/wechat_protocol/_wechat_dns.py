from __future__ import annotations

import ipaddress
import json
import socket
import time
from dataclasses import dataclass
from typing import Any as JsonValue
from urllib.parse import urlencode


_NEWDNS_HOST = "aedns.weixin.qq.com"
_NEWDNS_BACKUP_IP = "180.153.202.85"
_NEWDNS_PATH = "/cgi-bin/default/getdns"
_NEWDNS_USER_AGENT = "MicroMessenger Client"
_MAX_HTTP_RESPONSE = 16 * 1024 * 1024
_MAX_HTTP_HEADER = 64 * 1024


@dataclass(frozen=True, slots=True)
class Target:
    ip: str
    port: int


@dataclass(frozen=True, slots=True)
class DomainServers:
    ips: tuple[str, ...]
    protocols: dict[str, tuple[int, ...]]


def remaining(deadline: float) -> float:
    timeout = deadline - time.monotonic()
    if timeout <= 0:
        raise TimeoutError("operation timed out")
    return timeout


def connect_socket(host: str, port: int, deadline: float) -> socket.socket:
    sock = socket.create_connection((host, port), timeout=remaining(deadline))
    try:
        sock.settimeout(remaining(deadline))
    except BaseException:
        sock.close()
        raise
    return sock


def _recv(sock: socket.socket, deadline: float, size: int = 8192) -> bytes:
    sock.settimeout(remaining(deadline))
    return sock.recv(size)


def _read_to_eof(sock: socket.socket, initial: bytes, deadline: float) -> bytes:
    response = bytearray(initial)
    if len(response) > _MAX_HTTP_RESPONSE:
        raise ValueError("HTTP response is too large")
    while True:
        chunk = _recv(sock, deadline)
        if not chunk:
            return bytes(response)
        response.extend(chunk)
        if len(response) > _MAX_HTTP_RESPONSE:
            raise ValueError("HTTP response is too large")


def _decode_chunked(data: bytes) -> bytes:
    output = bytearray()
    offset = 0
    while True:
        line_end = data.find(b"\r\n", offset)
        if line_end < 0:
            raise ValueError("invalid chunked HTTP response")
        size_text = data[offset:line_end].split(b";", 1)[0].strip()
        try:
            chunk_size = int(size_text, 16)
        except ValueError as exc:
            raise ValueError("invalid chunked HTTP response") from exc
        if chunk_size < 0:
            raise ValueError("invalid chunked HTTP response")
        offset = line_end + 2
        if chunk_size == 0:
            trailer_end = data.find(b"\r\n\r\n", offset)
            if trailer_end >= 0:
                return bytes(output)
            if data[offset : offset + 2] == b"\r\n":
                return bytes(output)
            raise ValueError("invalid chunked HTTP response")
        chunk_end = offset + chunk_size
        if chunk_end + 2 > len(data) or data[chunk_end : chunk_end + 2] != b"\r\n":
            raise ValueError("invalid chunked HTTP response")
        output.extend(data[offset:chunk_end])
        if len(output) > _MAX_HTTP_RESPONSE:
            raise ValueError("HTTP response is too large")
        offset = chunk_end + 2


def read_http_response(sock: socket.socket, deadline: float) -> tuple[int, bytes]:
    received = bytearray()
    header_end = -1
    while header_end < 0:
        chunk = _recv(sock, deadline)
        if not chunk:
            raise ValueError("incomplete HTTP response")
        received.extend(chunk)
        header_end = received.find(b"\r\n\r\n")
        if header_end < 0 and len(received) > _MAX_HTTP_HEADER:
            raise ValueError("HTTP response headers are too large")
    if header_end > _MAX_HTTP_HEADER:
        raise ValueError("HTTP response headers are too large")

    header = bytes(received[:header_end])
    body_start = header_end + 4
    lines = header.split(b"\r\n")
    status_parts = lines[0].split()
    if len(status_parts) < 2 or not status_parts[0].startswith(b"HTTP/"):
        raise ValueError("invalid HTTP status line")
    try:
        status = int(status_parts[1])
    except ValueError as exc:
        raise ValueError("invalid HTTP status line") from exc

    headers: dict[bytes, bytes] = {}
    for line in lines[1:]:
        name, separator, value = line.partition(b":")
        if separator:
            headers[name.strip().lower()] = value.strip().lower()

    body = bytes(received[body_start:])
    transfer_encoding = headers.get(b"transfer-encoding", b"")
    if b"chunked" in transfer_encoding:
        raw = _read_to_eof(sock, body, deadline)
        body = _decode_chunked(raw)
    elif b"content-length" in headers:
        try:
            content_length = int(headers[b"content-length"])
        except ValueError as exc:
            raise ValueError("invalid HTTP content length") from exc
        if not 0 <= content_length <= _MAX_HTTP_RESPONSE:
            raise ValueError("invalid HTTP content length")
        if len(body) > content_length:
            body = body[:content_length]
        else:
            rest = bytearray(body)
            while len(rest) < content_length:
                chunk = _recv(sock, deadline, min(8192, content_length - len(rest)))
                if not chunk:
                    raise ValueError("truncated HTTP response")
                rest.extend(chunk)
            body = bytes(rest)
    else:
        body = _read_to_eof(sock, body, deadline)
    return status, body


def http_request(
    connect_host: str,
    port: int,
    host_header: str,
    method: str,
    path: str,
    headers: tuple[tuple[str, str], ...],
    body: bytes,
    deadline: float,
) -> tuple[int, bytes]:
    request_headers = [
        f"{method} {path} HTTP/1.0",
        f"Host: {host_header}",
        "Connection: close",
        *[f"{name}: {value}" for name, value in headers],
    ]
    if body and not any(name.lower() == "content-length" for name, _ in headers):
        request_headers.append(f"Content-Length: {len(body)}")
    request = ("\r\n".join(request_headers) + "\r\n\r\n").encode("ascii") + body
    with connect_socket(connect_host, port, deadline) as sock:
        sock.settimeout(remaining(deadline))
        sock.sendall(request)
        return read_http_response(sock, deadline)


def _parse_domain_list(value: JsonValue) -> dict[str, DomainServers]:
    if not isinstance(value, dict):
        raise ValueError("HTTPDNS response is malformed")
    dns = value.get("dns")
    if not isinstance(dns, dict) or dns.get("retcode", 0) != 0:
        raise ValueError("HTTPDNS response is malformed")
    domain_list = dns.get("domainlist")
    if not isinstance(domain_list, list):
        raise ValueError("HTTPDNS response is malformed")

    result: dict[str, DomainServers] = {}
    for item in domain_list:
        if not isinstance(item, dict):
            continue
        name = item.get("name")
        if not isinstance(name, str) or not name:
            continue

        addresses: list[str] = []
        raw_ips = item.get("iplist")
        if isinstance(raw_ips, list):
            for raw_ip in raw_ips:
                if not isinstance(raw_ip, dict):
                    continue
                ip = raw_ip.get("ip")
                if isinstance(ip, str):
                    try:
                        ipaddress.ip_address(ip)
                    except ValueError:
                        continue
                    if ip not in addresses:
                        addresses.append(ip)

        protocols: dict[str, tuple[int, ...]] = {}
        raw_protocols = item.get("protocollist")
        if isinstance(raw_protocols, list):
            for raw_protocol in raw_protocols:
                if not isinstance(raw_protocol, dict):
                    continue
                protocol = raw_protocol.get("name")
                raw_ports = raw_protocol.get("portlist")
                if not isinstance(protocol, str) or not protocol or not isinstance(raw_ports, list):
                    continue
                ports = tuple(
                    port
                    for port in raw_ports
                    if isinstance(port, int) and not isinstance(port, bool) and 0 < port <= 65535
                )
                if ports:
                    protocols[protocol] = ports
        result[name] = DomainServers(tuple(addresses), protocols)
    return result


def fetch_httpdns(deadline: float) -> dict[str, DomainServers] | None:
    query = urlencode(
        {
            "clientversion": 0,
            "devicetype": "Windows",
            "uin": 0,
            "format": "json",
        }
    )
    path = f"{_NEWDNS_PATH}?{query}"
    for index, connect_host in enumerate((_NEWDNS_HOST, _NEWDNS_BACKUP_IP)):
        attempts_left = 2 - index
        now = time.monotonic()
        try:
            attempt_deadline = min(deadline, now + remaining(deadline) / attempts_left)
            status, body = http_request(
                connect_host,
                80,
                _NEWDNS_HOST,
                "GET",
                path,
                (("User-Agent", _NEWDNS_USER_AGENT), ("Accept", "*/*")),
                b"",
                attempt_deadline,
            )
            if status != 200:
                continue
            return _parse_domain_list(json.loads(body))
        except (OSError, TimeoutError, ValueError):
            continue
    return None


def targets_for(
    domains: dict[str, DomainServers] | None,
    domain: str,
    protocol: str,
) -> list[Target]:
    if domains is None:
        return []
    servers = domains.get(domain)
    if servers is None:
        return []
    ports = servers.protocols.get(protocol, ())
    return [Target(ip, port) for ip in servers.ips for port in ports]
