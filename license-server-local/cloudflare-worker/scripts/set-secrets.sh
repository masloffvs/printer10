#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(cd ../.. && pwd)"
keys_dir="${LICENSE_SERVER_KEYS_DIR:-$root/license-server-local/production-keys}"
enc_key="$keys_dir/license-x25519-private.pem"
sign_key="$keys_dir/license-ed25519-private.pem"
for file in "$enc_key" "$sign_key"; do
  if [[ ! -f "$file" ]]; then
    echo "Missing $file; generate production keys first with node scripts/generate-production-keys.js" >&2
    exit 1
  fi
done

bunx wrangler secret put LICENSE_ENCRYPTION_PRIVATE_KEY < "$enc_key"
bunx wrangler secret put LICENSE_SIGNING_PRIVATE_KEY < "$sign_key"
