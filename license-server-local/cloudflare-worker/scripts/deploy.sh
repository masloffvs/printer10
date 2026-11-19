#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
if ! command -v bunx >/dev/null 2>&1; then
  echo "Bun is required so Wrangler can run through bunx." >&2
  exit 1
fi
if grep -q 'REPLACE_WITH_D1_DATABASE_ID' wrangler.jsonc; then
  echo "Set database_id in wrangler.jsonc first (create one with: wrangler d1 create setretailx-licenses)." >&2
  exit 1
fi

bunx wrangler d1 migrations apply LICENSES --remote
bunx wrangler deploy
