"""Cryptographic primitives for YYB's WeChat MMTLS transport.

The wire derivations and nonce/AAD layouts are ported from
``internal/protocol/mmtls_crypto.go`` in YYB-Go-Enhanced (commit
8a9497397abc7bf736be462f2e293f2bc6d4d0c0). They intentionally implement
that protocol's HKDF labels rather than TLS 1.3's differently encoded labels.
"""

from __future__ import annotations

import hashlib
import hmac
import os
import struct
from dataclasses import dataclass, field

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

_AES_IV_LEN = 12
_GCM_TAG_LEN = 16
_MAX_HKDF_SHA256_LEN = 255 * hashlib.sha256().digest_size

_LABEL_EXPANDED_SECRET = b"expanded secret"
_LABEL_HANDSHAKE_KEYS = b"handshake key expansion"
_LABEL_APP_DATA_KEYS = b"application data key expansion"
_LABEL_CLIENT_FINISHED = b"client finished"
_LABEL_SERVER_FINISHED = b"server finished"
_LABEL_PSK_ACCESS = b"PSK_ACCESS"
_LABEL_PSK_REFRESH = b"PSK_REFRESH"
ILINK_HKDF_SALT = b"security hdkf expand"


@dataclass(frozen=True, slots=True)
class TrafficKeys:
    """Directional traffic keys derived for one MMTLS handshake phase."""

    client_key: bytes = field(default=b"", repr=False)
    server_key: bytes = field(default=b"", repr=False)
    client_iv: bytes = field(default=b"", repr=False)
    server_iv: bytes = field(default=b"", repr=False)
    key: bytes = field(default=b"", repr=False)
    iv: bytes = field(default=b"", repr=False)


def hkdf_expand(prk: bytes, info: bytes, length: int) -> bytes:
    """Expand a SHA-256 HKDF pseudorandom key using the upstream info bytes."""
    if length < 0 or length > _MAX_HKDF_SHA256_LEN:
        raise ValueError("invalid HKDF output length")
    if length == 0:
        return b""
    output = bytearray()
    previous = b""
    counter = 1
    while len(output) < length:
        previous = hmac.digest(prk, previous + info + bytes((counter,)), "sha256")
        output.extend(previous)
        counter += 1
    return bytes(output[:length])


def hkdf_extract(salt: bytes, ikm: bytes) -> bytes:
    """Extract a SHA-256 HKDF pseudorandom key."""
    return hmac.digest(salt, ikm, "sha256")


def expand_label(secret: bytes, label: bytes, context: bytes, length: int) -> bytes:
    """Apply YYB's protocol-specific ``label || context`` HKDF expansion."""
    return hkdf_expand(secret, label + context, length)


def generate_ecdh() -> tuple[ec.EllipticCurvePrivateKey, bytes]:
    """Generate a P-256 key and its SEC1 uncompressed public-point encoding."""
    private_key = ec.generate_private_key(ec.SECP256R1())
    return private_key, private_key.public_key().public_bytes(
        encoding=serialization.Encoding.X962,
        format=serialization.PublicFormat.UncompressedPoint,
    )


def mmtls_secret(
    private_key: ec.EllipticCurvePrivateKey, peer_public_key: bytes
) -> bytes:
    """Derive the MMTLS shared secret (SHA-256 of P-256 ECDH)."""
    try:
        peer = ec.EllipticCurvePublicKey.from_encoded_point(ec.SECP256R1(), peer_public_key)
        shared = private_key.exchange(ec.ECDH(), peer)
    except (ValueError, TypeError) as error:
        raise ValueError("invalid MMTLS peer public key") from error
    return hashlib.sha256(shared).digest()


def nonce(iv: bytes, sequence: int) -> bytes:
    """XOR the big-endian 64-bit record sequence into the IV's final bytes."""
    if len(iv) != _AES_IV_LEN:
        raise ValueError("MMTLS IV must be 12 bytes")
    if not 0 <= sequence <= 0xFFFFFFFFFFFFFFFF:
        raise ValueError("MMTLS record sequence is out of range")
    sequence_bytes = struct.pack(">Q", sequence)
    result = bytearray(iv)
    for index, value in enumerate(sequence_bytes, start=_AES_IV_LEN - 8):
        result[index] ^= value
    return bytes(result)


def gcm_encrypt(key: bytes, iv: bytes, sequence: int, plaintext: bytes, aad: bytes) -> bytes:
    """AES-GCM encrypt using the MMTLS sequence-derived nonce."""
    try:
        return AESGCM(key).encrypt(nonce(iv, sequence), plaintext, aad)
    except ValueError as error:
        raise ValueError("invalid MMTLS AES-GCM parameters") from error


def gcm_decrypt(key: bytes, iv: bytes, sequence: int, ciphertext: bytes, aad: bytes) -> bytes:
    """AES-GCM decrypt and authenticate using the MMTLS record nonce."""
    try:
        return AESGCM(key).decrypt(nonce(iv, sequence), ciphertext, aad)
    except InvalidTag:
        raise
    except ValueError as error:
        raise ValueError("invalid MMTLS AES-GCM parameters") from error


def build_record_aad(sequence: int, content_type: int, ciphertext_length: int) -> bytes:
    """Build MMTLS record AAD: sequence, content type, version, ciphertext size."""
    if not 0 <= sequence <= 0xFFFFFFFFFFFFFFFF:
        raise ValueError("MMTLS record sequence is out of range")
    if not 0 <= content_type <= 0xFF:
        raise ValueError("invalid MMTLS record content type")
    if not 0 <= ciphertext_length <= 0xFFFF:
        raise ValueError("MMTLS ciphertext length is out of range")
    return struct.pack(">Q B 2s H", sequence, content_type, b"\xf1\x03", ciphertext_length)


def encrypt_record_payload(
    key: bytes, iv: bytes, sequence: int, content_type: int, plaintext: bytes
) -> bytes:
    """Encrypt a record payload with the protocol's exact AAD length."""
    ciphertext_length = len(plaintext) + _GCM_TAG_LEN
    aad = build_record_aad(sequence, content_type, ciphertext_length)
    return gcm_encrypt(key, iv, sequence, plaintext, aad)


def decrypt_record_payload(
    key: bytes, iv: bytes, sequence: int, content_type: int, ciphertext: bytes
) -> bytes:
    """Authenticate and decrypt a record payload; InvalidTag is preserved."""
    aad = build_record_aad(sequence, content_type, len(ciphertext))
    return gcm_decrypt(key, iv, sequence, ciphertext, aad)


def derive_master(secret: bytes, handshake_hash: bytes) -> bytes:
    return expand_label(secret, _LABEL_EXPANDED_SECRET, handshake_hash, 32)


def _split_bi_keys(key_block: bytes) -> TrafficKeys:
    if len(key_block) != 56:
        raise ValueError("MMTLS bidirectional key block must be 56 bytes")
    return TrafficKeys(
        client_key=key_block[0:16],
        server_key=key_block[16:32],
        client_iv=key_block[32:44],
        server_iv=key_block[44:56],
    )


def derive_handshake_keys(secret: bytes, handshake_hash: bytes) -> TrafficKeys:
    key_block = expand_label(secret, _LABEL_HANDSHAKE_KEYS, handshake_hash, 56)
    return _split_bi_keys(key_block)


def derive_app_keys(master: bytes, handshake_hash: bytes) -> TrafficKeys:
    key_block = expand_label(master, _LABEL_APP_DATA_KEYS, handshake_hash, 56)
    return _split_bi_keys(key_block)


def finished_verify_data(secret: bytes, is_client: bool, handshake_hash: bytes) -> bytes:
    """Return expected Finished data; callers MUST compare it in constant time."""
    label = _LABEL_CLIENT_FINISHED if is_client else _LABEL_SERVER_FINISHED
    finished_key = expand_label(secret, label, b"", 32)
    return hmac.digest(finished_key, handshake_hash, "sha256")


def derive_psk(secret: bytes, cert_verify_hash: bytes, psk_type: int) -> bytes:
    label = _LABEL_PSK_ACCESS if psk_type == 1 else _LABEL_PSK_REFRESH
    return expand_label(secret, label, cert_verify_hash, 32)


def derive_psk_one_way_keys(psk: bytes, label: bytes, handshake_hash: bytes) -> TrafficKeys:
    key_block = expand_label(psk, label, handshake_hash, 28)
    return TrafficKeys(key=key_block[:16], iv=key_block[16:28])


def aes_gcm_encrypt_layout(key: bytes, aad: bytes, plaintext: bytes) -> bytes:
    """Encrypt in YYB's blob layout: ciphertext body || 12-byte IV || 16-byte tag."""
    iv = os.urandom(_AES_IV_LEN)
    try:
        wire = AESGCM(key).encrypt(iv, plaintext, aad)
    except ValueError as error:
        raise ValueError("invalid AES-GCM parameters") from error
    ciphertext, tag = wire[:-_GCM_TAG_LEN], wire[-_GCM_TAG_LEN:]
    return ciphertext + iv + tag


def aes_gcm_decrypt_layout(key: bytes, aad: bytes, blob: bytes) -> bytes:
    """Authenticate and decrypt YYB's ciphertext || IV || tag blob layout."""
    if len(blob) < _AES_IV_LEN + _GCM_TAG_LEN:
        raise ValueError("AES-GCM blob is too short")
    ciphertext = blob[:-(_AES_IV_LEN + _GCM_TAG_LEN)]
    iv = blob[-(_AES_IV_LEN + _GCM_TAG_LEN) : -_GCM_TAG_LEN]
    tag = blob[-_GCM_TAG_LEN:]
    try:
        return AESGCM(key).decrypt(iv, ciphertext + tag, aad)
    except InvalidTag:
        raise
    except ValueError as error:
        raise ValueError("invalid AES-GCM parameters") from error


# Imported by tests and the transport to classify authenticated-decryption failures
# without inspecting or logging any response bytes.
__all__ = [
    "TrafficKeys",
    "aes_gcm_decrypt_layout",
    "aes_gcm_encrypt_layout",
    "build_record_aad",
    "derive_app_keys",
    "derive_handshake_keys",
    "derive_master",
    "derive_psk",
    "derive_psk_one_way_keys",
    "decrypt_record_payload",
    "encrypt_record_payload",
    "expand_label",
    "finished_verify_data",
    "generate_ecdh",
    "gcm_decrypt",
    "gcm_encrypt",
    "hkdf_expand",
    "hkdf_extract",
    "mmtls_secret",
    "nonce",
]
