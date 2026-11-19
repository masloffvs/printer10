CREATE TABLE IF NOT EXISTS licenses (
  license_key TEXT PRIMARY KEY,
  status TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
  device_public_key TEXT,
  device_id TEXT,
  activated_at TEXT,
  CHECK ((device_public_key IS NULL) = (device_id IS NULL))
);

CREATE INDEX IF NOT EXISTS licenses_status_idx ON licenses(status);
