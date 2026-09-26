#!/usr/bin/env python3
"""One-request TLS 1.2 reference host for the Android bootstrap channel-binding test."""

import base64
import json
import re
import socket
import ssl
import sys


HEADER_END = b"\r\n\r\n"
MAX_HEADER_BYTES = 16 * 1024
MAX_BODY_BYTES = 256 * 1024
PAIRING_BINDING = re.compile(r"^[A-Za-z0-9_-]{16,510}={0,2}$")
RESPONSE = json.dumps(
    {
        "protocol_version": 1,
        "state": "pending",
        "pairing_id": "pair-python-reference0001",
        "expires_at": 2_000_000_000.0,
        "pairing_session": "python-reference-session-0001",
    },
    separators=(",", ":"),
).encode("utf-8")


def read_request(stream):
    header = bytearray()
    while not header.endswith(HEADER_END):
        value = stream.read(1)
        if not value:
            raise RuntimeError("connection ended before HTTP headers")
        header += value
        if len(header) > MAX_HEADER_BYTES:
            raise RuntimeError("request headers exceeded the reference limit")

    lines = header[:-len(HEADER_END)].decode("ascii").split("\r\n")
    method, path, _ = lines[0].split(" ", 2)
    headers = {}
    for line in lines[1:]:
        name, value = line.split(":", 1)
        headers[name.strip().lower()] = value.strip()
    length = int(headers.get("content-length", "0"))
    if length < 0 or length > MAX_BODY_BYTES:
        raise RuntimeError("request body exceeded the reference limit")
    body = stream.read(length)
    if len(body) != length:
        raise RuntimeError("request body was truncated")
    return method, path, headers, body


def main():
    certificate, private_key = sys.argv[1:3]
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.maximum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(certificate, private_key)

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(10)
        print(f"READY\t{listener.getsockname()[1]}", flush=True)
        raw, _ = listener.accept()
        with raw, context.wrap_socket(raw, server_side=True) as tls:
            tls.settimeout(6)
            stream = tls.makefile("rb")
            method, path, headers, body = read_request(stream)
            binding = tls.get_channel_binding("tls-unique")
            if binding is None or len(binding) < 12:
                raise RuntimeError("Python could not export a valid tls-unique binding")
            encoded_binding = base64.urlsafe_b64encode(binding).decode("ascii")
            supplied_binding = headers.get("x-steamos-companion-tls-binding", "")
            if not PAIRING_BINDING.fullmatch(supplied_binding):
                raise RuntimeError("client binding header does not match the host syntax")
            if supplied_binding != encoded_binding:
                raise RuntimeError("client binding header differs from Python's tls-unique bytes")
            if method != "POST" or path != "/v1/pair/request":
                raise RuntimeError("expected the initial pairing request")
            request = json.loads(body)
            if "verification_nonce" not in request or "pairing_session" in request:
                raise RuntimeError("expected the first nonce-based pairing request")

            print(
                "RESULT\t{}\t{}\t{}\t{}\t{}".format(
                    tls.version(),
                    tls.cipher()[0],
                    str(tls.session_reused).lower(),
                    encoded_binding,
                    supplied_binding,
                ),
                flush=True,
            )
            response = (
                b"HTTP/1.0 200 OK\r\n"
                b"Content-Type: application/json\r\n"
                b"Cache-Control: no-store\r\n"
                b"Connection: close\r\n"
                + f"Content-Length: {len(RESPONSE)}\r\n\r\n".encode("ascii")
                + RESPONSE
            )
            tls.sendall(response)
            stream.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR\t{type(error).__name__}\t{error}", file=sys.stderr, flush=True)
        raise
