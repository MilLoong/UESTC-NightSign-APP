"""Emit deterministic, non-account test vectors from the Python reference."""
import json
import random
import sys
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'app/src/main/python'))
from wechat_protocol import _wechat_crypto as c, _wechat_wire as w
from wechat_protocol import _wechat_mmtls as transport
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

rng = random.Random(20261026)


def data(size):
    return bytes(rng.randrange(256) for _ in range(size))


vectors = {'crypto': [], 'codec': [], 'ecdh': [], 'business': [], 'early': []}
for i in range(32):
    secret, digest, key, iv, plain = data(32), data(32), data(16), data(12), data(i * 13)
    sequence = i * 65537
    traffic = c.derive_handshake_keys(secret, digest)
    master = c.derive_master(secret, digest)
    app = c.derive_app_keys(master, digest)
    cipher = AESGCM(key).encrypt(iv, plain, digest)
    vectors['crypto'].append({
        'secret': secret.hex(), 'hash': digest.hex(), 'key': key.hex(), 'iv': iv.hex(),
        'plain': plain.hex(), 'sequence': sequence, 'nonce': c.nonce(iv, sequence).hex(),
        'aad': c.build_record_aad(sequence, 23, len(plain) + 16).hex(),
        'cipher': c.encrypt_record_payload(key, iv, sequence, 23, plain).hex(),
        'layout': (cipher[:-16] + iv + cipher[-16:]).hex(),
        'master': master.hex(), 'handshake': (traffic.client_key + traffic.server_key + traffic.client_iv + traffic.server_iv).hex(),
        'application': (app.client_key + app.server_key + app.client_iv + app.server_iv).hex(),
        'finished': c.finished_verify_data(secret, True, digest).hex(),
        'psk': c.derive_psk(secret, digest, 1).hex(),
        'expand': c.hkdf_expand(secret, digest, i * 7).hex(),
    })

for size in (0, 1, 14, 15, 16, 269, 270, 271, 1024, 4096):
    payload = data(size)
    first, second, random_bytes = b'\x04' + data(64), b'\x04' + data(64), data(32)
    vectors['codec'].append({
        'payload': payload.hex(), 'lz4': w.lz4_all_literal(payload).hex(),
        'proto': (w.pb_var(1, 1029) + w.pb_len(2, payload)).hex(),
        'wpkg': w.wpkg_head({1: 1, 20: 1504}, {14: b'', 24: payload}).hex(),
        'record': w.build_record(23, payload).hex(),
        'handshake': w.build_handshake(1, payload).hex(),
        'shortlink': w.build_shortlink(1029, 16, payload, 0).hex(),
        'random': random_bytes.hex(), 'first': first.hex(), 'second': second.hex(),
        'hello': w.build_client_hello(random_bytes, 1792998000, first, second).hex(),
        'pskHello': w.build_psk_client_hello(random_bytes, 1792998000, payload).hex(),
        'jsLogin': w.build_jsapi_plaintext(12345678, 'wxaa78bf2905f01cee', b'', 0, None,
                                          b'wxd44977328b36e647', b'02-01-02-03-04-05').hex(),
    })

for scalar in (1, 2, 255, 65537):
    private = ec.derive_private_key(scalar, ec.SECP256R1())
    peer = ec.derive_private_key(999999, ec.SECP256R1()).public_key().public_bytes(
        serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    vectors['ecdh'].append({'scalar': scalar, 'peer': peer.hex(),
                            'secret': c.mmtls_secret(private, peer).hex()})

for index in range(4):
    scalar = index + 12345
    private = ec.derive_private_key(scalar, ec.SECP256R1())
    peer_private = ec.derive_private_key(77777, ec.SECP256R1())
    peer = peer_private.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
    okm, compressed, send, receive = data(56), w.lz4_all_literal(b'fixture-request'), data(16), data(16)
    raw_session = w.pb_len(1, send) + w.pb_len(2, receive) + w.pb_len(9, b'f9')
    manual = w.pb_len(3, w.pb_len(2, raw_session) + w.pb_len(3, w.pb_var(1, 12345678)))
    secret = c.mmtls_secret(private, peer)
    aad = c.hashlib.sha256(okm[24:56] + compressed + b'415' + peer + b'1').digest()
    with patch.object(c.os, 'urandom', return_value=bytes(12)):
        blob = c.aes_gcm_encrypt_layout(secret[:24], aad, w.lz4_all_literal(manual))
    hybrid = w.pb_len(1, w.pb_var(1, 415) + w.pb_len(2, peer)) + w.pb_var(2, 1) + w.pb_len(3, blob)
    login_response = w.wpkg_head({}, {}) + hybrid
    vectors['business'].append({'scalar': scalar, 'okm': okm.hex(), 'compressed': compressed.hex(),
                                'response': login_response.hex(), 'send': send.hex(), 'receive': receive.hex()})

    psk, random_bytes = data(32), data(32)
    entry = w.PSKEntry(1, psk, b'fixture-ticket', 7200, 1793005200)
    envelope = b'fixture-envelope'
    with patch.object(transport.time, 'time', return_value=1792998000), \
         patch.object(transport.secrets, 'token_bytes', return_value=random_bytes):
        request, _, hello, type8 = transport._build_0rtt_request(entry, envelope)
    server = w.build_handshake(2, b'fixture-server-hello')
    plain = w.pb_len(1, w.pb_var(1, 0)) + w.pb_len(2, w.pb_len(3, b'c' * 32))
    with patch.object(c.os, 'urandom', return_value=bytes(12)):
        response_blob = w.wpkg_head({}, {}) + c.aes_gcm_encrypt_layout(receive, b'', w.lz4_all_literal(plain))
    packet = w.build_shortlink(1029, 0, response_blob, 0)
    keys = c.derive_psk_one_way_keys(psk, b'handshake key expansion', c.hashlib.sha256(hello + server).digest())
    wire = w.build_record(22, server) + w.build_record(23, c.encrypt_record_payload(keys.key, keys.iv, 2, 23, packet))
    vectors['early'].append({'psk': psk.hex(), 'random': random_bytes.hex(), 'envelope': envelope.hex(),
                            'request': request.hex(), 'hello': hello.hex(), 'type8': type8.hex(),
                            'response': wire.hex(), 'receive': receive.hex()})

print(json.dumps(vectors, separators=(',', ':')))
