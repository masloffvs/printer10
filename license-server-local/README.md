# Local license verification server (development only)

This is a development harness, not a production license service. Generated keys and license data are local-only and Git-ignored.

Requirements: Bun 1.x. Run from this directory (key/data paths are resolved relative to these scripts):

```sh
bun run generate-keys.js
bun run server.js
```

Copy the printed `SETRETAILX_LICENSE_*` values to the repository `.env`. The generated license binds to the first device key that successfully activates it; to reset a development activation, edit `data/licenses.json` and remove `devicePublicKey`/`activatedAt` for that license. Set a record's `status` to `revoked` to reject future lease requests.

The client sends a binary frame over HTTP. Its request is ephemeral X25519 + HKDF-SHA256 + AES-256-GCM encrypted. The server response uses the same session key and AES-GCM, and contains an Ed25519-signed lease bound to a one-time challenge, the installation public key, and a SHA-256 hash of the operating system's machine identifier. The client pins the server's X25519 encryption key and Ed25519 signature-verification key from local files. Binary framing is only transport encoding; the cryptography provides confidentiality, integrity and server authentication.

The client requires successful online validation at startup and stores the latest signed lease in the user's home directory. If the server goes offline, that signed lease permits startup only until its maximum four-hour expiration. Clock rollback beyond five minutes is rejected. No local business data is deleted on failure.

**Do not expose this development server to an untrusted network.** It has no TLS, admin API, rate limiting, durable database, audit trail, key rotation or hardened operational controls. HTTP metadata (IP, endpoint, timing and traffic size) remains visible. For production, use an externally operated service and protected private keys; distribute only the server public keys to clients.
