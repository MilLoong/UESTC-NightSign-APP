"""wx.login packet codecs and MMTLS record framing.

The wx.login structures are ported from ``internal/protocol/bytes.go``,
``mmtls_protocol.go`` and the manual-auth/js-login portion of ``ilink.go`` in
YYB-Go-Enhanced (commit 8a9497397abc7bf736be462f2e293f2bc6d4d0c0). Phone and
wx-data operation packet helpers are intentionally omitted.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import os
import struct
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric import ec

from ._wechat_crypto import (
    ILINK_HKDF_SALT,
    aes_gcm_decrypt_layout,
    aes_gcm_encrypt_layout,
    generate_ecdh,
    hkdf_expand,
    hkdf_extract,
)

_RECORD_VERSION = 0xF103
_CT_ALERT = 0x15
_CT_HANDSHAKE = 0x16
_CT_APP_DATA = 0x17
_CT_PSK_HANDSHAKE = 0x19
_HS_CLIENT_HELLO = 0x01
_HS_FINISHED = 0x14
_CIPHER_ECDHE = 0xC02B
_CIPHER_PSK = 0x00A8
_EXT_KEY_SHARE = 0x01
_GROUP_SECP256R1 = 0x01
_GROUP_2 = 0x02
_KEY_LEN = 65
_SHORTLINK_MAGIC = 0x1110
_DEFAULT_VERSION = 0x076D
_TRANSFER_CMD = 0x0B41
_JS_LOGIN_CMD_ID = 1029
_CLIENT_VERSION = 524545
_APP_VERSION = 1901
_SESSION_CLIENT_VERSION = 1661404927

_SERVER_PUB = bytes.fromhex(
    "04ef87876d6478b15f1796eab12068610541173b7176b67f1dcc86683e901acd44"
    "d18b4ac36938251d0812dd0cf842aa2d6cbb8115712d1c0087dcefc14a44cd58"
)
_HOST_APP_ID_DEFAULT = b"wxd44977328b36e647"
_ILINK_DEVICE = b"ilinkapp_060000b7b93f6c"
_TRANSFER_URL = b"/ilink/ilinkapp/mp/wxaruntime_transfer"
_TRANSFER_HOST = b"shortcloud.weixin.com"
_JS_LOGIN_URL = b"/cgi-bin/mmbiz-bin/js-login"
_WINDOWS_PLUGIN = b"WindowsxWebPlugin"
_WINDOWS_NAME = b"Windows"
_UNIFIED_PC_WINDOWS = b"UnifiedPCWindows"
_SESSION_KEY_LITERAL = b"sessionkey"

_MAX_LZ4_OUTPUT = 16 * 1024 * 1024
_MAX_PROTO_FIELD = (1 << 29) - 1
_UINT64_MAX = (1 << 64) - 1


@dataclass(frozen=True, slots=True)
class LoginMeta:
    ticket: bytes = field(repr=False)
    device_id: bytes = field(repr=False)
    host_app_id: bytes = field(repr=False)
    raw: bytes = field(repr=False)


@dataclass(slots=True)
class ManualAuthTemp:
    ephemeral: ec.EllipticCurvePrivateKey | None = field(default=None, repr=False)
    okm: bytes = field(default=b"", repr=False)
    comp_body: bytes = field(default=b"", repr=False)


@dataclass(frozen=True, slots=True)
class AppSession:
    send_key: bytes = field(repr=False)
    recv_key: bytes = field(repr=False)
    f9: bytes = field(repr=False)
    uin: int = field(repr=False)
    ticket: bytes = field(repr=False)
    device_id: bytes = field(default=b"", repr=False)
    host_app_id: bytes = field(default=b"", repr=False)


@dataclass(frozen=True, slots=True)
class PSKEntry:
    psk_type: int
    pre_shared_key: bytes = field(repr=False)
    ticket_entry: bytes = field(repr=False)
    lifetime: int
    expired_at: int


@dataclass(frozen=True, slots=True)
class NewSessionTicketEntry:
    psk_type: int
    lifetime: int
    ticket_entry: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class Record:
    content_type: int
    payload: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class ServerHello:
    server_random: bytes = field(repr=False)
    cipher: int
    server_group: int
    server_pub_key: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class ShortlinkPacket:
    total_len: int
    magic: int
    ver: int
    cmd: int
    seq: int
    body: bytes = field(repr=False)



def encode_varint(value: int) -> bytes:
    """Encode an unsigned protobuf/base-128 varint."""
    if not 0 <= value <= _UINT64_MAX:
        raise ValueError("varint value is out of range")
    result = bytearray()
    while value >= 0x80:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_varint(data: bytes, offset: int = 0) -> tuple[int, int]:
    """Read one bounded uint64 varint, returning its value and next offset."""
    if not 0 <= offset <= len(data):
        raise ValueError("invalid varint offset")
    value = 0
    for index in range(10):
        if offset >= len(data):
            raise ValueError("truncated varint")
        byte = data[offset]
        offset += 1
        if index == 9 and byte > 1:
            raise ValueError("varint overflow")
        value |= (byte & 0x7F) << (index * 7)
        if byte < 0x80:
            return value, offset
    raise ValueError("varint overflow")


def _validate_field_number(field: int) -> None:
    if not 1 <= field <= _MAX_PROTO_FIELD:
        raise ValueError("protobuf field number is out of range")


def pb_len(field: int, data: bytes) -> bytes:
    _validate_field_number(field)
    return encode_varint((field << 3) | 2) + encode_varint(len(data)) + data


def pb_var(field: int, value: int) -> bytes:
    _validate_field_number(field)
    return encode_varint(field << 3) + encode_varint(value)


def pb_parse(data: bytes) -> dict[int, int | bytes]:
    """Decode supported protobuf wire values and safely skip fixed-width fields."""
    result: dict[int, int | bytes] = {}
    offset = 0
    while offset < len(data):
        tag, offset = read_varint(data, offset)
        field_number, wire_type = tag >> 3, tag & 7
        if not 1 <= field_number <= _MAX_PROTO_FIELD:
            raise ValueError("invalid protobuf field number")
        if wire_type == 0:
            value, offset = read_varint(data, offset)
            if value >= 1 << 63:
                value -= 1 << 64
            result[field_number] = value
        elif wire_type == 1:
            end = offset + 8
            if end > len(data):
                raise ValueError("truncated protobuf fixed64 field")
            result[field_number] = int.from_bytes(data[offset:end], "little")
            offset = end
        elif wire_type == 2:
            length, offset = read_varint(data, offset)
            end = offset + length
            if end > len(data):
                raise ValueError("truncated protobuf bytes field")
            result[field_number] = data[offset:end]
            offset = end
        elif wire_type == 5:
            end = offset + 4
            if end > len(data):
                raise ValueError("truncated protobuf fixed32 field")
            result[field_number] = int.from_bytes(data[offset:end], "little")
            offset = end
        else:
            raise ValueError("unsupported protobuf wire type")
    return result




def lz4_all_literal(data: bytes) -> bytes:
    """Encode an LZ4 block consisting of one literal-only sequence."""
    length = len(data)
    if length < 15:
        return bytes((length << 4,)) + data
    result = bytearray((0xF0,))
    remaining = length - 15
    while remaining >= 255:
        result.append(255)
        remaining -= 255
    result.append(remaining)
    result.extend(data)
    return bytes(result)


def lz4_decompress(data: bytes, *, max_output_size: int = _MAX_LZ4_OUTPUT) -> bytes:
    """Decode an LZ4 block, rejecting truncation, invalid offsets and expansion bombs."""
    if max_output_size < 0:
        raise ValueError("invalid LZ4 output limit")
    output = bytearray()
    offset = 0
    while offset < len(data):
        token = data[offset]
        offset += 1
        literal_length = token >> 4
        if literal_length == 15:
            while True:
                if offset >= len(data):
                    raise ValueError("truncated LZ4 literal length")
                extension = data[offset]
                offset += 1
                literal_length += extension
                if extension != 255:
                    break
        literal_end = offset + literal_length
        if literal_end > len(data):
            raise ValueError("truncated LZ4 literals")
        if len(output) + literal_length > max_output_size:
            raise ValueError("LZ4 output exceeds limit")
        output.extend(data[offset:literal_end])
        offset = literal_end
        if offset == len(data):
            break
        if offset + 2 > len(data):
            raise ValueError("truncated LZ4 match offset")
        match_offset = int.from_bytes(data[offset : offset + 2], "little")
        offset += 2
        if match_offset == 0 or match_offset > len(output):
            raise ValueError("invalid LZ4 match offset")
        match_length = (token & 0x0F) + 4
        if token & 0x0F == 15:
            while True:
                if offset >= len(data):
                    raise ValueError("truncated LZ4 match length")
                extension = data[offset]
                offset += 1
                match_length += extension
                if extension != 255:
                    break
        if len(output) + match_length > max_output_size:
            raise ValueError("LZ4 output exceeds limit")
        match_start = len(output) - match_offset
        for index in range(match_length):
            output.append(output[match_start + index])
    return bytes(output)




def parse_login_buffer(login_buffer: str) -> LoginMeta:
    """Decode the base64 login_buffer envelope and require ticket/device identity."""
    try:
        raw = base64.b64decode(login_buffer, validate=True)
    except (binascii.Error, ValueError) as error:
        raise ValueError("invalid login_buffer encoding") from error
    fields = pb_parse(raw)
    ticket = fields.get(1)
    device_id = fields.get(2)
    if not isinstance(ticket, bytes) or not ticket:
        raise ValueError("login_buffer missing ticket")
    if not isinstance(device_id, bytes) or not device_id:
        raise ValueError("login_buffer missing device identifier")
    host_app_id = fields.get(3, b"")
    if not isinstance(host_app_id, bytes):
        raise ValueError("invalid login_buffer host application identifier")
    return LoginMeta(ticket=ticket, device_id=device_id, host_app_id=host_app_id, raw=raw)


def _random_session_device() -> bytes:
    mac = bytearray(os.urandom(6))
    mac[0] = (mac[0] | 0x02) & 0xFE
    return "-".join(f"{part:02X}" for part in mac).encode("ascii")


def _build_manual_auth_request(login_buffer: str, app_device_id: bytes) -> bytes:
    meta = parse_login_buffer(login_buffer)
    app_base = pb_len(1, app_device_id) + pb_var(2, _APP_VERSION)
    third_app = pb_len(1, meta.ticket)
    return (
        pb_len(1, app_base)
        + pb_len(3, third_app)
        + pb_var(4, 4)
        + pb_len(6, b"")
        + pb_var(7, 0)
        + pb_var(8, 6)
    )


def wpkg_head(ints: dict[int, int], byts: dict[int, bytes]) -> bytes:
    result = bytearray(encode_varint(1))
    for field in sorted(ints):
        _validate_field_number(field)
        result.extend(encode_varint(field))
        result.extend(encode_varint(ints[field]))
    result.extend(encode_varint(0))
    for field in sorted(byts):
        _validate_field_number(field)
        value = byts[field]
        result.extend(encode_varint(field))
        result.extend(encode_varint(len(value)))
        result.extend(value)
    prefix_length = len(result)
    result.append(0)
    result.extend(encode_varint(prefix_length + 1))
    return bytes(result)


def _manual_auth_head(device_id: bytes) -> bytes:
    ints = {
        1: 1,
        2: 0,
        3: 0,
        4: 0,
        5: _CLIENT_VERSION,
        6: 11,
        7: 0,
        8: 0,
        9: 0,
        10: 1,
        11: 0,
        12: 0,
        13: 0,
        17: 0,
        18: 1,
        20: 1504,
        21: 0,
        22: 0,
        23: 0,
        25: 17,
        26: 4,
        28: 1,
        29: 1,
        30: 0,
    }
    return wpkg_head(ints, {14: b"", 24: device_id, 27: b""})


def _hybrid_ecdh_encrypt(
    plaintext: bytes, server_public_key: bytes, temp: ManualAuthTemp
) -> bytes:
    ephemeral, ephemeral_public_key = generate_ecdh()

    try:
        peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_public_key)
        shared = ephemeral.exchange(ec.ECDH(), peer)
    except (ValueError, TypeError) as error:
        raise ValueError("invalid manual-auth server public key") from error
    secret = hashlib.sha256(shared).digest()
    hash1 = hashlib.sha256(b"1" + b"415" + ephemeral_public_key).digest()
    content_encryption_key = os.urandom(32)
    encrypted_key = aes_gcm_encrypt_layout(secret[:24], hash1, content_encryption_key)
    prk = hkdf_extract(ILINK_HKDF_SALT, content_encryption_key)
    okm = hkdf_expand(prk, hash1, 56)
    hash2 = hashlib.sha256(
        b"1" + b"415" + ephemeral_public_key + encrypted_key
    ).digest()
    compressed_body = lz4_all_literal(plaintext)
    encrypted_body = aes_gcm_encrypt_layout(okm[:24], hash2, compressed_body)
    ecdh_key = pb_var(1, 415) + pb_len(2, ephemeral_public_key)
    temp.ephemeral = ephemeral
    temp.okm = okm
    temp.comp_body = compressed_body
    return (
        pb_var(1, 1)
        + pb_len(2, ecdh_key)
        + pb_len(3, encrypted_key)
        + pb_len(4, b"")
        + pb_len(5, encrypted_body)
    )




def build_login_body(
    login_buffer: str,
    device_id: bytes,
    app_device_id: bytes,
    temp: ManualAuthTemp,
) -> bytes:
    """Build the encrypted ManualAuth request and its wxlogin WPKG header."""
    request = _build_manual_auth_request(login_buffer, app_device_id)
    encrypted = _hybrid_ecdh_encrypt(request, _SERVER_PUB, temp)
    return _manual_auth_head(device_id) + encrypted


def decode_wpkg_head(data: bytes) -> tuple[dict[int, int], dict[int, bytes], int]:
    """Decode a WPKG prefix; the returned offset points just after its trailer."""
    version, offset = read_varint(data)
    if version != 1:
        raise ValueError("unsupported WPKG header version")
    integer_fields: dict[int, int] = {}
    while True:
        field, offset = read_varint(data, offset)
        if field == 0:
            break
        if field > _MAX_PROTO_FIELD:
            raise ValueError("invalid WPKG integer field")
        value, offset = read_varint(data, offset)
        integer_fields[field] = value
    byte_fields: dict[int, bytes] = {}
    while True:
        field, offset = read_varint(data, offset)
        if field == 0:
            break
        if field > _MAX_PROTO_FIELD:
            raise ValueError("invalid WPKG bytes field")
        length, offset = read_varint(data, offset)
        end = offset + length
        if end > len(data):
            raise ValueError("truncated WPKG bytes field")
        byte_fields[field] = data[offset:end]
        offset = end
    trailer_offset = offset
    declared_offset, offset = read_varint(data, offset)
    if declared_offset != trailer_offset:
        raise ValueError("invalid WPKG header trailer")
    return integer_fields, byte_fields, offset


def parse_login_response(body: bytes, temp: ManualAuthTemp) -> bytes:
    """Verify/decrypt the server's hybrid-ECDH ManualAuth response."""
    hybrid: bytes | None = None
    try:
        _, _, header_length = decode_wpkg_head(body)
    except ValueError:
        header_length = -1
    if 0 <= header_length < len(body) and body[header_length] == 0x0A:
        hybrid = body[header_length:]
    else:
        marker = b"\x08\x9f\x03\x12\x41\x04"
        marker_offset = body.find(marker)
        if marker_offset >= 2:
            hybrid = body[marker_offset - 2 :]
    if hybrid is None:
        raise ValueError("ManualAuth hybrid response is missing")
    return _hybrid_ecdh_decrypt(hybrid, temp)


def _hybrid_ecdh_decrypt(response: bytes, temp: ManualAuthTemp) -> bytes:
    if temp.ephemeral is None:
        raise ValueError("missing ManualAuth ephemeral key")
    if len(temp.okm) != 56 or not temp.comp_body:
        raise ValueError("invalid ManualAuth temporary key material")
    fields = pb_parse(response)
    encoded_key = fields.get(1)
    if not isinstance(encoded_key, bytes):
        raise ValueError("ManualAuth response is missing its server key")
    key_fields = pb_parse(encoded_key)
    server_public_key = key_fields.get(2)
    if not isinstance(server_public_key, bytes):
        raise ValueError("ManualAuth response is missing its server public point")
    credential_type_value = fields.get(2, 1)
    if not isinstance(credential_type_value, int):
        raise ValueError("invalid ManualAuth credential type")
    ciphertext = fields.get(3)
    if not isinstance(ciphertext, bytes):
        raise ValueError("ManualAuth response is missing ciphertext")
    try:
        peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_public_key)
        shared = temp.ephemeral.exchange(ec.ECDH(), peer)
    except (ValueError, TypeError) as error:
        raise ValueError("invalid ManualAuth server public point") from error
    secret = hashlib.sha256(shared).digest()
    aad = hashlib.sha256(
        temp.okm[24:56]
        + temp.comp_body
        + b"415"
        + server_public_key
        + str(credential_type_value).encode("ascii")
    ).digest()
    compressed = aes_gcm_decrypt_layout(secret[:24], aad, ciphertext)
    return lz4_decompress(compressed)


def extract_session(manual_auth_response: bytes) -> AppSession:
    """Extract validated application session keys and identity from ManualAuth."""
    outer = pb_parse(manual_auth_response)
    field_three = outer.get(3)
    if not isinstance(field_three, bytes):
        raise ValueError("ManualAuth response is missing session data")
    body = pb_parse(field_three)
    session_block = body.get(2)
    identity_block = body.get(3)
    if not isinstance(session_block, bytes) or not isinstance(identity_block, bytes):
        raise ValueError("ManualAuth response is missing session identity")
    session = pb_parse(session_block)
    identity = pb_parse(identity_block)
    send_key = session.get(1)
    recv_key = session.get(2)
    f9 = session.get(9, b"")
    ticket = session.get(12, b"")
    uin = identity.get(1)
    if not isinstance(send_key, bytes) or len(send_key) not in (16, 24, 32):
        raise ValueError("ManualAuth send key is invalid")
    if not isinstance(recv_key, bytes) or len(recv_key) not in (16, 24, 32):
        raise ValueError("ManualAuth receive key is invalid")
    if not isinstance(f9, bytes) or not isinstance(ticket, bytes):
        raise ValueError("ManualAuth session metadata is invalid")
    if not isinstance(uin, int) or uin <= 0:
        raise ValueError("ManualAuth user identity is invalid")
    return AppSession(
        send_key=send_key,
        recv_key=recv_key,
        f9=f9,
        uin=uin,
        ticket=ticket,
        device_id=_ILINK_DEVICE,
    )


def _session_info(uin: int, platform: bytes, session_device: bytes) -> bytes:
    if not 0 <= uin <= _UINT64_MAX:
        raise ValueError("WeChat user identifier is out of range")
    uin32 = uin & 0xFFFFFFFF
    return (
        pb_len(1, _SESSION_KEY_LITERAL)
        + pb_var(2, uin32)
        + pb_len(3, session_device)
        + pb_var(4, _SESSION_CLIENT_VERSION)
        + pb_len(5, platform)
        + pb_var(6, 0)
    )


def build_jsapi_plaintext(
    uin: int,
    app_id: str,
    url: bytes,
    cmd: int,
    body: bytes | None,
    host_app_id: bytes,
    extra: bytes | None,
) -> bytes:
    """Build js-login transfer plaintext (``extra`` is session-device identity)."""
    if not url:
        url = _JS_LOGIN_URL
    if cmd == 0:
        cmd = _JS_LOGIN_CMD_ID
    if not host_app_id:
        host_app_id = _HOST_APP_ID_DEFAULT
    session_device = extra if extra else _random_session_device()
    app_id_bytes = app_id.encode("utf-8")
    if body is None:
        body = (
            pb_len(1, _session_info(uin, _UNIFIED_PC_WINDOWS, session_device))
            + pb_len(2, app_id_bytes)
            + pb_var(4, 1)
            + pb_len(5, b"")
            + pb_len(6, b"")
            + pb_var(7, 1)
        )
    return (
        pb_len(1, _session_info(uin, _WINDOWS_NAME, session_device))
        + pb_len(2, url)
        + pb_len(3, host_app_id)
        + pb_var(4, 5)
        + pb_len(5, body)
        + pb_len(6, app_id_bytes)
        + pb_var(7, cmd)
        + pb_var(8, 1610627409)
        + pb_len(9, _WINDOWS_PLUGIN)
        + pb_var(10, 573651281)
    )


def _session_wpkg_head(uin: int, f9: bytes, device_id: bytes) -> bytes:
    if not 0 <= uin <= _UINT64_MAX:
        raise ValueError("WeChat user identifier is out of range")
    if not device_id:
        device_id = _ILINK_DEVICE
    ints = {
        1: 1,
        2: uin,
        3: 0,
        4: 0,
        5: _CLIENT_VERSION,
        6: 11,
        7: 0,
        8: 0,
        9: 0,
        10: 1,
        11: 0,
        12: 0,
        13: 0,
        17: 0,
        18: 1,
        20: 1504,
        21: 0,
        22: uin,
        23: 0,
        25: 16,
        26: 4,
        28: 1,
        29: 1,
        30: 0,
    }
    return wpkg_head(ints, {14: b"", 24: device_id, 27: f9})


def build_transfer_packet(session: AppSession, plaintext: bytes) -> bytes:
    """Encrypt a js-login payload and wrap it in the shortlink transfer envelope."""
    encrypted = aes_gcm_encrypt_layout(
        session.send_key, b"", lz4_all_literal(plaintext)
    )
    shortlink = build_shortlink(
        _TRANSFER_CMD,
        0,
        _session_wpkg_head(session.uin, session.f9, session.device_id) + encrypted,
        _DEFAULT_VERSION,
    )
    if len(_TRANSFER_URL) > 0xFFFF or len(_TRANSFER_HOST) > 0xFFFF:
        raise ValueError("transfer envelope field is too long")
    envelope = (
        struct.pack(">H", len(_TRANSFER_URL))
        + _TRANSFER_URL
        + struct.pack(">H", len(_TRANSFER_HOST))
        + _TRANSFER_HOST
        + struct.pack(">I", len(shortlink))
        + shortlink
    )
    return struct.pack(">I", len(envelope)) + envelope




def build_record(content_type: int, payload: bytes) -> bytes:
    if not 0 <= content_type <= 0xFF or len(payload) > 0xFFFF:
        raise ValueError("invalid MMTLS record")
    return struct.pack(">BHH", content_type, _RECORD_VERSION, len(payload)) + payload


def _known_content_type(content_type: int) -> bool:
    return content_type in {
        _CT_ALERT,
        _CT_HANDSHAKE,
        _CT_APP_DATA,
        _CT_PSK_HANDSHAKE,
        0x14,
    }


def parse_records(data: bytes) -> tuple[list[Record], int]:
    """Parse complete MMTLS frames and leave an incomplete suffix for later reads."""
    records: list[Record] = []
    offset = 0
    while offset + 5 <= len(data):
        content_type, version, length = struct.unpack_from(">BHH", data, offset)
        if version != _RECORD_VERSION or not _known_content_type(content_type):
            raise ValueError("invalid MMTLS record header")
        end = offset + 5 + length
        if end > len(data):
            break
        records.append(Record(content_type=content_type, payload=data[offset + 5 : end]))
        offset = end
    return records, offset


def build_handshake(message_type: int, body: bytes) -> bytes:
    if not 0 <= message_type <= 0xFF or len(body) + 1 > 0xFFFFFFFF:
        raise ValueError("invalid MMTLS handshake message")
    payload = bytes((message_type,)) + body
    return struct.pack(">I", len(payload)) + payload


def split_handshake(payload: bytes) -> tuple[int, bytes]:
    if len(payload) < 5:
        raise ValueError("MMTLS handshake payload is too short")
    total = struct.unpack_from(">I", payload)[0]
    if total == 0 or total + 4 > len(payload):
        raise ValueError("invalid MMTLS handshake length")
    return payload[4], payload[5 : total + 4]


def build_client_hello(
    client_random: bytes, timestamp: int, g1_public: bytes, g2_public: bytes
) -> bytes:
    if len(client_random) != 32 or len(g1_public) != _KEY_LEN or len(g2_public) != _KEY_LEN:
        raise ValueError("invalid ClientHello key or random length")
    if not 0 <= timestamp <= 0xFFFFFFFF:
        raise ValueError("ClientHello timestamp is out of range")
    body = bytearray(b"\x03\xf1\x01")
    body.extend(struct.pack(">H", _CIPHER_ECDHE))
    body.extend(client_random)
    body.extend(struct.pack(">I", timestamp))
    key_shares = bytearray(b"\x00\x10\x02")
    for group, public_key in ((_GROUP_SECP256R1, g1_public), (_GROUP_2, g2_public)):
        share = struct.pack(">IH", group, len(public_key)) + public_key
        key_shares.extend(struct.pack(">I", len(share)))
        key_shares.extend(share)
    key_shares.extend(b"\x00\x00\x00\x01")
    extension = bytes((_EXT_KEY_SHARE,)) + struct.pack(">I", len(key_shares)) + key_shares
    body.extend(struct.pack(">I", len(extension)))
    body.extend(extension)
    return build_handshake(_HS_CLIENT_HELLO, bytes(body))


def build_psk_client_hello(client_random: bytes, timestamp: int, ticket_entry: bytes) -> bytes:
    if len(client_random) != 32:
        raise ValueError("ClientHello random must be 32 bytes")
    if not 0 <= timestamp <= 0xFFFFFFFF or len(ticket_entry) > 0xFFFFFFFF:
        raise ValueError("PSK ClientHello field is out of range")
    body = bytearray(b"\x03\xf1\x01")
    body.extend(struct.pack(">H", _CIPHER_PSK))
    body.extend(client_random)
    body.extend(struct.pack(">I", timestamp))
    extension_data = b"\x00\x0f\x01" + struct.pack(">I", len(ticket_entry)) + ticket_entry
    extension = bytes((_EXT_KEY_SHARE,)) + struct.pack(">I", len(extension_data)) + extension_data
    body.extend(struct.pack(">I", len(extension)))
    body.extend(extension)
    return build_handshake(_HS_CLIENT_HELLO, bytes(body))


def _extract_key_shares(data: bytes) -> list[bytes]:
    keys: list[bytes] = []
    offset = 0
    while offset + 67 <= len(data):
        if data[offset : offset + 3] == b"\x00\x41\x04":
            keys.append(data[offset + 2 : offset + 67])
            offset += 67
        else:
            offset += 1
    return keys


def parse_server_hello(body: bytes) -> ServerHello:
    if len(body) < 40:
        raise ValueError("ServerHello is too short")
    cipher = struct.unpack_from(">H", body, 2)[0]
    server_random = body[4:36]
    extension_length = struct.unpack_from(">I", body, 36)[0]
    extension_start = 40
    extension_end = extension_start + extension_length
    if extension_end > len(body):
        raise ValueError("ServerHello extensions are truncated")
    extension = body[extension_start:extension_end]
    server_group = _GROUP_SECP256R1
    server_public_key = b""
    if extension and extension[0] == _EXT_KEY_SHARE:
        if len(extension) < 7:
            raise ValueError("ServerKeyShare is truncated")
        declared_extension_length = struct.unpack_from(">I", extension, 1)[0]
        if declared_extension_length > len(extension) - 5:
            raise ValueError("ServerKeyShare extension is truncated")
        cursor = 7
        if cursor + 6 > len(extension):
            raise ValueError("ServerKeyShare key is truncated")
        server_group = struct.unpack_from(">I", extension, cursor)[0]
        cursor += 4
        key_length = struct.unpack_from(">H", extension, cursor)[0]
        cursor += 2
        if cursor + key_length > len(extension):
            raise ValueError("ServerKeyShare public point is truncated")
        server_public_key = extension[cursor : cursor + key_length]
    else:
        key_shares = _extract_key_shares(extension)
        if key_shares:
            server_public_key = key_shares[0]
    if not server_public_key:
        raise ValueError("ServerHello is missing its public point")
    try:
        ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), server_public_key)
    except ValueError as error:
        raise ValueError("invalid ServerHello public point") from error
    return ServerHello(
        server_random=server_random,
        cipher=cipher,
        server_group=server_group,
        server_pub_key=server_public_key,
    )


def build_finished(verify_data: bytes) -> bytes:
    if len(verify_data) != 32:
        raise ValueError("Finished verify data must be 32 bytes")
    return build_handshake(_HS_FINISHED, struct.pack(">H", len(verify_data)) + verify_data)


def parse_finished(body: bytes) -> bytes:
    if len(body) < 2:
        raise ValueError("Finished message is too short")
    length = struct.unpack_from(">H", body)[0]
    if length != len(body) - 2:
        raise ValueError("invalid Finished verify-data length")
    return body[2:]


def parse_new_session_ticket(body: bytes) -> tuple[NewSessionTicketEntry, ...]:
    if not body:
        raise ValueError("NewSessionTicket body is empty")
    count = body[0]
    offset = 1
    entries: list[NewSessionTicketEntry] = []
    for _ in range(count):
        if offset + 4 > len(body):
            raise ValueError("truncated NewSessionTicket entry length")
        entry_length = struct.unpack_from(">I", body, offset)[0]
        offset += 4
        end = offset + entry_length
        if end > len(body) or entry_length < 5:
            raise ValueError("invalid NewSessionTicket entry")
        entry = body[offset:end]
        offset = end
        lifetime = struct.unpack_from(">I", entry, 1)[0]
        entries.append(
            NewSessionTicketEntry(
                psk_type=entry[0], lifetime=lifetime, ticket_entry=entry
            )
        )
    if offset != len(body):
        raise ValueError("unexpected trailing NewSessionTicket data")
    return tuple(entries)


def build_shortlink(cmd: int, seq: int, body: bytes, version: int) -> bytes:
    if version == 0:
        version = _DEFAULT_VERSION
    if not 0 <= cmd <= 0xFFFFFFFF or not 0 <= seq <= 0xFFFFFFFF:
        raise ValueError("shortlink command or sequence is out of range")
    if not 0 <= version <= 0xFFFF or len(body) + 16 > 0xFFFFFFFF:
        raise ValueError("shortlink packet field is out of range")
    total_length = 16 + len(body)
    return struct.pack(">IHHII", total_length, _SHORTLINK_MAGIC, version, cmd, seq) + body


def parse_shortlink(packet: bytes) -> ShortlinkPacket:
    if len(packet) < 16:
        raise ValueError("shortlink packet is too short")
    total_length, magic, version, cmd, sequence = struct.unpack_from(">IHHII", packet)
    if total_length < 16 or total_length > len(packet):
        raise ValueError("invalid shortlink packet length")
    return ShortlinkPacket(
        total_len=total_length,
        magic=magic,
        ver=version,
        cmd=cmd,
        seq=sequence,
        body=packet[16:total_length],
    )


def session_decrypt(body: bytes, recv_key: bytes) -> bytes:
    """Authenticate/decrypt a transfer response, then decompress its LZ4 block."""
    offsets: list[int] = []
    try:
        _, _, header_length = decode_wpkg_head(body)
    except ValueError:
        header_length = -1
    if header_length >= 0:
        offsets.append(header_length)
    offsets.extend(range(min(len(body), 220)))
    seen: set[int] = set()
    for offset in offsets:
        if offset in seen or offset < 0 or offset >= len(body):
            continue
        seen.add(offset)
        blob = body[offset:]
        if len(blob) < 29:
            continue
        try:
            compressed = aes_gcm_decrypt_layout(recv_key, b"", blob)
            return lz4_decompress(compressed)
        except (InvalidTag, ValueError):
            continue
    raise ValueError("js-login response could not be authenticated")


def extract_code(response: bytes) -> bytes | None:
    """Extract the js-login authorization code from its nested protobuf body."""
    fields = pb_parse(response)
    nested = fields.get(2)
    if not isinstance(nested, bytes):
        return None
    nested_fields = pb_parse(nested)
    code = nested_fields.get(3)
    return code if isinstance(code, bytes) else None


__all__ = [
    "AppSession",
    "LoginMeta",
    "ManualAuthTemp",
    "NewSessionTicketEntry",
    "PSKEntry",
    "Record",
    "ServerHello",
    "ShortlinkPacket",
    "build_client_hello",
    "build_finished",
    "build_handshake",
    "build_jsapi_plaintext",
    "build_login_body",
    "build_psk_client_hello",
    "build_record",
    "build_shortlink",
    "build_transfer_packet",
    "decode_wpkg_head",
    "encode_varint",
    "extract_code",
    "extract_session",
    "lz4_all_literal",
    "lz4_decompress",
    "parse_finished",
    "parse_login_buffer",
    "parse_login_response",
    "parse_new_session_ticket",
    "parse_records",
    "parse_server_hello",
    "parse_shortlink",
    "pb_len",
    "pb_parse",
    "pb_var",
    "read_varint",
    "session_decrypt",
    "split_handshake",
    "wpkg_head",
]
