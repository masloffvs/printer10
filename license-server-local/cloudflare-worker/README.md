# Cloudflare license Worker

This Worker implements the same `/v1/lease` binary protocol as `../server.js` and the Java client. It uses Workers Web Crypto (X25519, HKDF-SHA-256, AES-256-GCM and Ed25519) and stores license records and first-device bindings in Cloudflare D1. The license signing/encryption private keys are Worker secrets; the local plaintext key files are never deployed or committed.

## Prerequisites

- Bun and Node.js 20+ (Wrangler is run through `bunx`, no global install needed)
- A Cloudflare account authenticated with `wrangler login`
- A D1 database

Run all commands from this directory unless otherwise noted.

## First deployment

1. Create the D1 database and copy its ID into `wrangler.jsonc`:

   ```sh
   bunx wrangler d1 create setretailx-licenses
   ```

2. Generate unique production keys and an initial active license. The script refuses to overwrite existing files and writes secrets under the ignored `license-server-local/production-keys/` and `license-server-local/data/cloudflare-licenses.json` paths:

   ```sh
   node scripts/generate-production-keys.js
   ```

   Keep the generated license key and public key files secure. Back up both private key files offline; losing or rotating either key invalidates existing client configuration or signed cached leases. Do not reuse the development keys in `license-server-local/keys/`.

3. Deploy the D1 schema and Worker:

   ```sh
   bash scripts/deploy.sh
   ```

   `wrangler.jsonc` contains a placeholder database ID on purpose. Deployment stops until it is replaced. Wrangler applies the D1 migration remotely before deploying the Worker.

4. Add the private keys as encrypted Worker secrets, then import the generated license record into D1:

   ```sh
   bash scripts/set-secrets.sh
   node scripts/import-licenses.js
   ```

   The importer uses the local-only `data/cloudflare-licenses.json`, passes SQL through a mode-0600 temporary file, and does not print license keys. Re-running it synchronizes license status while preserving an already-bound device. To create more licenses, add records to that JSON file and rerun it. Use status `revoked` to reject new leases.

5. Configure the app using the generated `SETRETAILX_LICENSE_*` values. The URL should be the deployed Worker URL ending in `/v1/lease`, and the public key paths point to `production-keys/license-x25519-public.pem` and `production-keys/license-ed25519-public.pem`.

Check deployment health at `https://<worker-host>/health`; it should return `ok`.

## Local Worker development

After setting `database_id`, apply a local migration and start Wrangler:

```sh
bunx wrangler d1 migrations apply LICENSES --local
bunx wrangler dev
```

For local testing, copy `.dev.vars.example` to `.dev.vars` and set `LICENSE_ENCRYPTION_PRIVATE_KEY` and `LICENSE_SIGNING_PRIVATE_KEY` to PEM contents. Never commit or deploy `.dev.vars`.

## Operations and security

- License traffic and lease semantics remain compatible with the current client: requests are challenge-bound and device-signed, and successful leases are Ed25519-signed and session-encrypted for up to four hours.
- D1 is the source of truth. Back it up and restrict Cloudflare account access. Keep `LICENSE_*_PRIVATE_KEY` values only in Worker secrets and secure offline backups.
- `/health` is intentionally public and reveals no license information. The lease endpoint accepts only bounded binary POST bodies and exposes generic rejection responses.
- This Worker has no administration API. Add, revoke and audit records with controlled D1 scripts/processes; never expose a bulk license listing endpoint to clients.
- Cloudflare and D1 availability become dependencies. The app's existing signed lease cache permits offline startup only until its four-hour lease expires.
