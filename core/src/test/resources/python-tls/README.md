# TLS reference fixtures

Test fixtures only. `fake-host-cert.pem` and `fake-host-key.pem` are a throwaway self-signed certificate (`CN=steamos-companion-fake-host`) and its private key, used by `PythonTlsReferenceTest` to run `tls_reference_server.py` on loopback and compare the channel-binding bytes the Android client sends with CPython's independent OpenSSL stack.

The key is not a credential. No Steam Deck, phone or release build trusts it, and nothing outside the JVM unit tests reads it. It is committed on purpose so the test is deterministic; a secret scanner that flags it is flagging this file.
