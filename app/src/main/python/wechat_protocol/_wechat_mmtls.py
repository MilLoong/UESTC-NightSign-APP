from __future__ import annotations

import hashlib
import hmac
import json
import math
import secrets
import socket
import struct
import time
from collections import deque
from dataclasses import dataclass, replace

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric import ec
from typing import Any as JsonValue

from ._wechat_crypto import (
    derive_app_keys,
    derive_handshake_keys,
    derive_master,
    derive_psk,
    derive_psk_one_way_keys,
    decrypt_record_payload,
    encrypt_record_payload,
    finished_verify_data,
    generate_ecdh,
    mmtls_secret,
)
from ._wechat_dns import Target, connect_socket, fetch_httpdns, http_request, targets_for
from ._wechat_wire import (
    AppSession,
    LoginMeta,
    ManualAuthTemp,
    NewSessionTicketEntry,
    PSKEntry,
    Record,
    ShortlinkPacket,
    build_client_hello,
    build_finished,
    build_jsapi_plaintext,
    build_login_body,
    build_psk_client_hello,
    build_record,
    build_shortlink,
    build_transfer_packet,
    extract_code,
    extract_session,
    parse_finished,
    parse_login_buffer,
    parse_login_response,
    parse_new_session_ticket,
    parse_records,
    parse_server_hello,
    parse_shortlink,
    pb_parse,
    session_decrypt,
    split_handshake,
)
from .wechat_models import WeChatAuthorizationError, WeChatError, WeChatProtocolError


_LONG_LINK_DOMAIN = "longcloud.weixin.com"
_SHORT_LINK_DOMAIN = "shortcloud.weixin.com"
_MMTLS_PROTOCOL = "mmtlsovertcp"
_SHORTLINK_FALLBACK_IP = "120.241.131.173"
_LONG_LINK_FALLBACK_PORT = 443
_SHORTLINK_PORT = 80

_CT_ALERT = 0x15
_CT_HANDSHAKE = 0x16
_CT_APP_DATA = 0x17
_CT_PSK_HANDSHAKE = 0x19
_HS_SERVER_HELLO = 0x02
_HS_NEW_SESSION_TICKET = 0x04
_HS_CERT_VERIFY = 0x0F
_HS_FINISHED = 0x14
_GROUP_SECP256R1 = 1
_CIPHER_ECDHE = 0xC02B
_CMD_MANUAL_AUTH = 0x0D7D
_CMD_JS_LOGIN = 1029
_SHORTLINK_MAGIC = 0x1110
_SHORTLINK_VERSION = 0x076D
_EARLY_KEYS_LABEL = b"early data key expansion"
_HANDSHAKE_KEYS_LABEL = b"handshake key expansion"
_TYPE8_PREFIX = bytes(
    (0x00, 0x00, 0x00, 0x10, 0x08, 0x00, 0x00, 0x00, 0x0B, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x12)
)
_EARLY_ALERT = bytes((0x00, 0x00, 0x00, 0x03, 0x00, 0x01, 0x01))
_MAX_RECORD_BUFFER = 2 * 65535


@dataclass(frozen=True, slots=True)
class _LoginState:
    session: AppSession
    psk: PSKEntry


class MmtlsConnection:
    def __init__(self, conn: socket.socket, deadline: float) -> None:
        self._conn = conn
        self._deadline = deadline
        self._rxbuf = bytearray()
        self._records: deque[Record] = deque()
        self._transcript = bytearray()
        self._tx_seq = 0
        self._rx_seq = 0
        self._app_seq = 0
        self._client_private_key: ec.EllipticCurvePrivateKey | None = None
        self._secret = b""
        self._cert_verify_hash: bytes | None = None
        self._final_hash: bytes | None = None
        self._new_session_tickets: list[NewSessionTicketEntry] = []
        self._handshake_client_key = b""
        self._handshake_client_iv = b""
        self._handshake_server_key = b""
        self._handshake_server_iv = b""
        self._app_client_key = b""
        self._app_client_iv = b""
        self._app_server_key = b""
        self._app_server_iv = b""

    def _set_timeout(self) -> None:
        remaining = self._deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("MMTLS operation timed out")
        self._conn.settimeout(remaining)

    def _send_raw(self, data: bytes) -> None:
        self._set_timeout()
        self._conn.sendall(data)

    def _send_plain(self, content_type: int, payload: bytes) -> None:
        self._send_raw(build_record(content_type, payload))
        self._tx_seq += 1

    def _send_encrypted(self, content_type: int, plaintext: bytes, key: bytes, iv: bytes) -> None:
        ciphertext = encrypt_record_payload(key, iv, self._tx_seq, content_type, plaintext)
        self._send_raw(build_record(content_type, ciphertext))
        self._tx_seq += 1

    def _next_record(self) -> Record:
        while not self._records:
            parsed, consumed = parse_records(bytes(self._rxbuf))
            if parsed:
                self._records.extend(parsed)
                del self._rxbuf[:consumed]
                break
            if len(self._rxbuf) > _MAX_RECORD_BUFFER:
                raise ValueError("MMTLS record buffer is too large")
            self._set_timeout()
            chunk = self._conn.recv(65536)
            if not chunk:
                raise EOFError("MMTLS peer closed the connection")
            self._rxbuf.extend(chunk)
        return self._records.popleft()

    def _recv_plain(self) -> tuple[int, bytes]:
        record = self._next_record()
        self._rx_seq += 1
        return record.content_type, record.payload

    def _recv_decrypted(self, key: bytes, iv: bytes) -> tuple[int, bytes]:
        record = self._next_record()
        plaintext = decrypt_record_payload(key, iv, self._rx_seq, record.content_type, record.payload)
        self._rx_seq += 1
        return record.content_type, plaintext

    def _feed(self, data: bytes) -> None:
        self._transcript.extend(data)

    def _transcript_hash(self) -> bytes:
        return hashlib.sha256(self._transcript).digest()

    def handshake(self) -> None:
        private_key_1, public_key_1 = generate_ecdh()
        _, public_key_2 = generate_ecdh()
        client_hello = build_client_hello(
            secrets.token_bytes(32), int(time.time()), public_key_1, public_key_2
        )
        self._client_private_key = private_key_1
        self._feed(client_hello)
        self._send_plain(_CT_HANDSHAKE, client_hello)

        content_type, server_hello_payload = self._recv_plain()
        if content_type != _CT_HANDSHAKE:
            raise ValueError("unexpected MMTLS handshake record")
        message_type, server_hello_body = split_handshake(server_hello_payload)
        if message_type != _HS_SERVER_HELLO:
            raise ValueError("MMTLS ServerHello was not received")
        self._feed(server_hello_payload)
        server_hello = parse_server_hello(server_hello_body)
        if server_hello.cipher != _CIPHER_ECDHE or server_hello.server_group != _GROUP_SECP256R1:
            raise ValueError("unsupported MMTLS ServerHello parameters")
        self._secret = mmtls_secret(self._client_private_key, server_hello.server_pub_key)
        handshake_keys = derive_handshake_keys(self._secret, self._transcript_hash())
        self._handshake_client_key = handshake_keys.client_key
        self._handshake_client_iv = handshake_keys.client_iv
        self._handshake_server_key = handshake_keys.server_key
        self._handshake_server_iv = handshake_keys.server_iv

        for _ in range(64):
            content_type, plaintext = self._recv_decrypted(
                self._handshake_server_key, self._handshake_server_iv
            )
            if content_type == _CT_ALERT:
                raise ValueError("MMTLS peer rejected the handshake")
            if content_type != _CT_HANDSHAKE:
                raise ValueError("unexpected encrypted MMTLS handshake record")
            message_type, message_body = split_handshake(plaintext)
            if message_type == _HS_FINISHED:
                final_hash = self._transcript_hash()
                master = derive_master(self._secret, final_hash)
                app_keys = derive_app_keys(master, final_hash)
                expected = finished_verify_data(self._secret, False, final_hash)
                actual = parse_finished(message_body)
                if not hmac.compare_digest(actual, expected):
                    raise ValueError("MMTLS ServerFinished verification failed")
                self._final_hash = final_hash
                self._app_client_key = app_keys.client_key
                self._app_client_iv = app_keys.client_iv
                self._app_server_key = app_keys.server_key
                self._app_server_iv = app_keys.server_iv
                self._feed(plaintext)
                return

            self._feed(plaintext)
            if message_type == _HS_CERT_VERIFY:
                self._cert_verify_hash = self._transcript_hash()
            elif message_type == _HS_NEW_SESSION_TICKET:
                self._new_session_tickets.extend(parse_new_session_ticket(message_body))
        raise ValueError("MMTLS server flight exceeded the message limit")

    def finish_handshake(self) -> None:
        if self._final_hash is None:
            raise ValueError("MMTLS server handshake has not completed")
        # Both Finished proofs use the transcript before ServerFinished.
        verify_data = finished_verify_data(self._secret, True, self._final_hash)
        self._send_encrypted(
            _CT_HANDSHAKE,
            build_finished(verify_data),
            self._handshake_client_key,
            self._handshake_client_iv,
        )

    def send_app(self, command: int, body: bytes) -> None:
        packet = build_shortlink(command, self._app_seq, body, _SHORTLINK_VERSION)
        self._app_seq += 1
        self._send_encrypted(_CT_APP_DATA, packet, self._app_client_key, self._app_client_iv)

    def recv_app(self) -> ShortlinkPacket:
        content_type, plaintext = self._recv_decrypted(self._app_server_key, self._app_server_iv)
        if content_type != _CT_APP_DATA:
            raise ValueError("unexpected MMTLS application record")
        packet = parse_shortlink(plaintext)
        if packet.magic != _SHORTLINK_MAGIC or packet.total_len != len(plaintext):
            raise ValueError("invalid MMTLS application packet")
        return packet

    def take_access_psk(self) -> PSKEntry:
        if self._cert_verify_hash is None:
            raise ValueError("MMTLS certificate transcript is unavailable")
        now = int(time.time())
        for ticket in self._new_session_tickets:
            psk = derive_psk(self._secret, self._cert_verify_hash, ticket.psk_type)
            entry = PSKEntry(
                psk_type=ticket.psk_type,
                pre_shared_key=psk,
                ticket_entry=ticket.ticket_entry,
                lifetime=ticket.lifetime,
                expired_at=now + ticket.lifetime,
            )
            if entry.psk_type == 1 and entry.expired_at > now:
                return entry
        raise ValueError("MMTLS access PSK was not issued")






def _ordered_longlink_targets(targets: list[Target]) -> list[Target]:
    preferred_ports = (443, 8080, 80, 5000)
    ordered: list[Target] = []
    seen: set[Target] = set()
    for port in preferred_ports:
        for target in targets:
            if target.port == port and target not in seen:
                seen.add(target)
                ordered.append(target)
    for target in targets:
        if target not in seen:
            seen.add(target)
            ordered.append(target)

    fallback = Target(_LONG_LINK_DOMAIN, _LONG_LINK_FALLBACK_PORT)
    ordered = [target for target in ordered if target != fallback]
    if len(ordered) >= 6:
        ordered = ordered[:5]
    ordered.append(fallback)
    return ordered


def _shortlink_targets(targets: list[Target]) -> list[Target]:
    result: list[Target] = []
    seen: set[str] = set()
    for target in targets:
        if target.port == _SHORTLINK_PORT and target.ip not in seen:
            seen.add(target.ip)
            result.append(target)
    if _SHORTLINK_FALLBACK_IP not in seen:
        result.append(Target(_SHORTLINK_FALLBACK_IP, _SHORTLINK_PORT))
    return result


def _login_target(login_buffer: str, meta: LoginMeta, target: Target, deadline: float) -> _LoginState:
    conn = connect_socket(target.ip, target.port, deadline)
    try:
        client = MmtlsConnection(conn, deadline)
        client.handshake()
        client.finish_handshake()

        temp = ManualAuthTemp()
        body = build_login_body(login_buffer, meta.device_id, secrets.token_bytes(32), temp)
        client.send_app(_CMD_MANUAL_AUTH, body)
        try:
            response = client.recv_app()
        except (InvalidTag, ValueError) as exc:
            raise WeChatProtocolError("WeChat manual authorization response was invalid") from exc
        if response.cmd != _CMD_MANUAL_AUTH:
            raise WeChatAuthorizationError("WeChat rejected manual authorization")
        try:
            manual_auth_response = parse_login_response(response.body, temp)
            session = extract_session(manual_auth_response)
            psk = client.take_access_psk()
        except (InvalidTag, ValueError) as exc:
            raise WeChatProtocolError("WeChat manual authorization response was invalid") from exc
        session = replace(session, device_id=meta.device_id, host_app_id=meta.host_app_id)
        return _LoginState(session=session, psk=psk)
    finally:
        conn.close()


def _build_0rtt_request(entry: PSKEntry, envelope: bytes) -> tuple[bytes, bytes, bytes, bytes]:
    if entry.psk_type != 1 or entry.expired_at <= int(time.time()):
        raise ValueError("access PSK is expired")
    timestamp = int(time.time()) & 0xFFFFFFFF
    client_hello = build_psk_client_hello(
        secrets.token_bytes(32), timestamp, entry.ticket_entry
    )
    early_hash = hashlib.sha256(client_hello).digest()
    early_keys = derive_psk_one_way_keys(entry.pre_shared_key, _EARLY_KEYS_LABEL, early_hash)
    type8 = _TYPE8_PREFIX + struct.pack(">I", timestamp)

    body = bytearray()
    body.extend(build_record(_CT_PSK_HANDSHAKE, client_hello))
    type8_ciphertext = encrypt_record_payload(
        early_keys.key, early_keys.iv, 1, _CT_PSK_HANDSHAKE, type8
    )
    body.extend(build_record(_CT_PSK_HANDSHAKE, type8_ciphertext))
    envelope_ciphertext = encrypt_record_payload(
        early_keys.key, early_keys.iv, 2, _CT_APP_DATA, envelope
    )
    body.extend(build_record(_CT_APP_DATA, envelope_ciphertext))
    alert_ciphertext = encrypt_record_payload(
        early_keys.key, early_keys.iv, 3, _CT_ALERT, _EARLY_ALERT
    )
    body.extend(build_record(_CT_ALERT, alert_ciphertext))
    return bytes(body), entry.pre_shared_key, client_hello, type8


def _check_business_error(response: bytes) -> None:
    if response.lstrip().startswith(b"{"):
        try:
            value: JsonValue = json.loads(response)
        except (UnicodeDecodeError, json.JSONDecodeError):
            value = None
        if isinstance(value, dict):
            for key in ("errcode", "errCode", "error_code", "retcode", "ret"):
                code = value.get(key)
                if isinstance(code, int) and not isinstance(code, bool) and code != 0:
                    raise WeChatAuthorizationError("WeChat rejected wx.login")

    try:
        fields = pb_parse(response)
    except ValueError as exc:
        raise WeChatProtocolError("WeChat wx.login response was malformed") from exc

    base_response = fields.get(1)
    error_code: int | None = base_response if isinstance(base_response, int) else None
    if isinstance(base_response, bytes):
        try:
            nested = pb_parse(base_response)
        except ValueError as exc:
            raise WeChatProtocolError("WeChat wx.login response was malformed") from exc
        nested_code = nested.get(1)
        if isinstance(nested_code, int):
            error_code = nested_code
    if error_code not in (None, 0):
        raise WeChatAuthorizationError("WeChat rejected wx.login")


def _response_code(response: bytes) -> str:
    _check_business_error(response)
    code = extract_code(response)
    if not code:
        raise WeChatProtocolError("WeChat wx.login response did not contain a code")
    try:
        result = code.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise WeChatProtocolError("WeChat wx.login code was invalid") from exc
    if not result or not result.strip() or any(ord(char) < 0x20 for char in result):
        raise WeChatProtocolError("WeChat wx.login code was invalid")
    return result


def parse_0rtt_response(
    response_body: bytes,
    psk: bytes,
    client_hello: bytes,
    type8: bytes,
    recv_key: bytes,
) -> str:
    records, consumed = parse_records(response_body)
    if consumed != len(response_body):
        raise WeChatProtocolError("WeChat shortlink response was truncated")
    server_hello: bytes | None = None
    app_data: bytes | None = None
    for record in records:
        if record.content_type == _CT_HANDSHAKE and server_hello is None:
            server_hello = record.payload
        elif record.content_type == _CT_APP_DATA and app_data is None:
            app_data = record.payload
    if server_hello is None or app_data is None:
        raise WeChatProtocolError("WeChat shortlink response did not contain application data")
    try:
        message_type, _ = split_handshake(server_hello)
    except ValueError as exc:
        raise WeChatProtocolError("WeChat shortlink ServerHello was malformed") from exc
    if message_type != _HS_SERVER_HELLO:
        raise WeChatProtocolError("WeChat shortlink response did not contain ServerHello")

    transcripts = (
        client_hello + server_hello,
        client_hello + type8 + server_hello,
        client_hello + server_hello + type8,
    )
    for transcript in transcripts:
        handshake_hash = hashlib.sha256(transcript).digest()
        handshake_keys = derive_psk_one_way_keys(
            psk, _HANDSHAKE_KEYS_LABEL, handshake_hash
        )
        for sequence in (2, 1, 3):
            try:
                plaintext = decrypt_record_payload(
                    handshake_keys.key,
                    handshake_keys.iv,
                    sequence,
                    _CT_APP_DATA,
                    app_data,
                )
            except (InvalidTag, ValueError):
                continue
            try:
                shortlink = parse_shortlink(plaintext)
            except ValueError:
                shortlink = None
            if (
                shortlink is not None
                and shortlink.magic == _SHORTLINK_MAGIC
                and shortlink.total_len == len(plaintext)
            ):
                try:
                    response = session_decrypt(shortlink.body, recv_key)
                except ValueError:
                    pass
                else:
                    return _response_code(response)
            try:
                response = session_decrypt(plaintext, recv_key)
            except ValueError:
                continue
            return _response_code(response)
    raise WeChatProtocolError("WeChat shortlink response could not be authenticated")


def _send_0rtt(
    target: Target,
    psk: PSKEntry,
    recv_key: bytes,
    envelope: bytes,
    deadline: float,
) -> str:
    if deadline <= time.monotonic():
        raise TimeoutError("WeChat shortlink request timed out")

    request_body, raw_psk, client_hello, type8 = _build_0rtt_request(psk, envelope)
    timestamp_path = f"/mmtls/{int(time.time()) & 0xFFFFFFFF:08x}"
    headers = (
        ("Accept", "*/*"),
        ("Cache-Control", "no-cache"),
        ("Content-Length", str(len(request_body))),
        ("Content-Type", "application/octet-stream"),
        ("Upgrade", "mmtls"),
        ("User-Agent", "MicroMessenger Client"),
        ("X-Online-Host", _SHORT_LINK_DOMAIN),
    )
    status, response = http_request(
        target.ip,
        target.port,
        _SHORT_LINK_DOMAIN,
        "POST",
        timestamp_path,
        headers,
        request_body,
        deadline,
    )
    if status != 200:
        raise WeChatProtocolError("WeChat shortlink rejected the login request")
    return parse_0rtt_response(response, raw_psk, client_hello, type8, recv_key)


def _bounded_deadline(overall_deadline: float, fraction: float, ceiling: float) -> float:
    now = time.monotonic()
    return min(overall_deadline, now + min(ceiling, max(0.001, (overall_deadline - now) * fraction)))


def _get_code(login_buffer: str, app_id: str, deadline: float) -> str:
    try:
        meta = parse_login_buffer(login_buffer)
    except ValueError as exc:
        raise WeChatAuthorizationError("WeChat login buffer was invalid") from exc

    dns_deadline = _bounded_deadline(deadline, 0.15, 5.0)
    domains = fetch_httpdns(dns_deadline)
    longlink_targets = _ordered_longlink_targets(
        targets_for(domains, _LONG_LINK_DOMAIN, _MMTLS_PROTOCOL)
    )

    login_deadline = _bounded_deadline(deadline, 0.65, 60.0)
    login_state: _LoginState | None = None
    for index, target in enumerate(longlink_targets):
        time_left = login_deadline - time.monotonic()
        if time_left <= 0:
            break
        attempt_deadline = min(
            login_deadline,
            time.monotonic() + time_left / (len(longlink_targets) - index),
        )
        try:
            login_state = _login_target(login_buffer, meta, target, attempt_deadline)
            break
        except (WeChatAuthorizationError, WeChatProtocolError):
            raise
        except (OSError, EOFError, TimeoutError, ValueError, InvalidTag):
            continue
    if login_state is None:
        raise WeChatProtocolError("Unable to establish a WeChat MMTLS session")

    shortlink_targets = _shortlink_targets(
        targets_for(domains, _SHORT_LINK_DOMAIN, "http")
    )
    plaintext = build_jsapi_plaintext(
        login_state.session.uin,
        app_id,
        b"/cgi-bin/mmbiz-bin/js-login",
        _CMD_JS_LOGIN,
        None,
        login_state.session.host_app_id,
        None,
    )
    envelope = build_transfer_packet(login_state.session, plaintext)

    failures = 0
    for index, target in enumerate(shortlink_targets):
        time_left = deadline - time.monotonic()
        if time_left <= 0:
            break
        attempt_deadline = time.monotonic() + time_left / (len(shortlink_targets) - index)
        try:
            return _send_0rtt(
                target,
                login_state.psk,
                login_state.session.recv_key,
                envelope,
                attempt_deadline,
            )
        except (WeChatAuthorizationError, WeChatProtocolError):
            raise
        except (OSError, EOFError, TimeoutError, ValueError, InvalidTag):
            failures += 1
    if failures:
        raise WeChatProtocolError("Unable to complete the WeChat shortlink login")
    raise WeChatProtocolError("WeChat login timed out")


def get_code(login_buffer: str, app_id: str, *, timeout: float = 30.0) -> str:
    """Perform ManualAuth and 0-RTT js-login, returning its wx.login code."""
    try:
        timeout_value = float(timeout)
    except OverflowError:
        raise WeChatProtocolError("WeChat login timeout must be a finite positive number") from None
    if not math.isfinite(timeout_value) or timeout_value <= 0:
        raise WeChatProtocolError("WeChat login timeout must be a finite positive number")
    if not login_buffer:
        raise WeChatAuthorizationError("WeChat login buffer is empty")
    if not app_id:
        raise WeChatProtocolError("WeChat app ID is empty")

    deadline = time.monotonic() + timeout_value
    try:
        return _get_code(login_buffer, app_id, deadline)
    except WeChatError:
        raise
    except (OSError, EOFError, TimeoutError, ValueError, InvalidTag, struct.error) as exc:
        if time.monotonic() >= deadline or isinstance(exc, TimeoutError):
            raise WeChatProtocolError("WeChat login timed out") from None
        raise WeChatProtocolError("WeChat login protocol exchange failed") from None


__all__ = ["get_code"]
