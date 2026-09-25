# androidTest

The instrumented tier runs against `FakeHost`, a BouncyCastle TLS server on loopback that serves the vendored `protocol/` bundle (packaged as test assets) and scripts approval, rejection, expiry, 429, lost replies, and journal pruning. It never reads a sibling Decky checkout.

`assets/fake-host/` holds its test-only TLS identity: a self-signed RSA-2048 certificate with a CN only, matching the shape of the Decky host certificate. It protects nothing and must never be referenced from `main`.

`LanMatrixTest` is skipped unless `scripts/lan-matrix.sh` passes its instrumentation arguments.
